package br.unb.baja.telemetry.gerador

import br.unb.baja.telemetry.domain.can.FrameEncoder
import br.unb.baja.telemetry.domain.can.SinaisDoBaja
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.LocalDate
import java.util.UUID
import kotlin.math.roundToLong
import kotlin.system.exitProcess

/** Um frame pronto para virar JSON. */
private data class FramePronto(val t: Long, val id: Int, val data: String)

private fun ByteArray.hex() = joinToString("") { "%02X".format(it) }

/**
 * Se passa pelo ESP32: gera telemetria plausivel e despeja no /ingest.
 *
 * Existe porque o carro nao fica disponivel para desenvolvimento (ADR-005) --
 * nao esta no laboratorio de madrugada, nem rodando quando o CI executa.
 *
 * Uso:
 *   ./gradlew gerador
 *   ./gradlew gerador --args="--duracao=60 --url=http://localhost:8081 --reenviar=3"
 */
fun main(args: Array<String>) {
    val opcao = { nome: String, padrao: String ->
        args.firstOrNull { it.startsWith("--$nome=") }?.substringAfter('=') ?: padrao
    }

    val duracao = opcao("duracao", "60").toInt()
    val url = opcao("url", "http://localhost:8081")
    val sessao = opcao("sessao", "${LocalDate.now()}-gerador-sintetico")
    val device = opcao("device", "esp32-sintetico")
    // A cada N lotes, reenvia o MESMO batchId -- simula a resposta ter se perdido
    // e o firmware ter retentado (ADR-008). 0 desliga.
    val reenviarACada = opcao("reenviar", "0").toInt()

    println("Gerador sintetico -- se passando pelo ESP32")
    println("  destino  : $url/api/v1/ingest")
    println("  sessao   : $sessao")
    println("  duracao  : ${duracao}s")
    if (reenviarACada > 0) println("  reenvio  : a cada $reenviarACada lotes, com o mesmo batchId")
    println()

    val carro = Carro()
    val cliente = ClienteIngest(url, sessao, device)
    val inicio = System.currentTimeMillis()

    // Taxas do docs/03. Note que os tres frames do DBC provisorio dao 125 msg/s;
    // o carro real, com quatro nos, fica na casa dos 500.
    val taxas = listOf(
        SinaisDoBaja.ID_MOTOR to 100,
        SinaisDoBaja.ID_DINAMICA to 20,
        SinaisDoBaja.ID_GPS to 5,
    )

    var lote = mutableListOf<FramePronto>()
    var loteAberto = System.currentTimeMillis()
    var nLote = 0
    var totalFrames = 0L

    // Amostra a 100 Hz e emite cada frame na sua taxa.
    var tick = 0L
    while (System.currentTimeMillis() - inicio < duracao * 1000L) {
        val decorrido = (System.currentTimeMillis() - inicio) / 1000.0
        val e = carro.estadoEm(decorrido)
        val agora = System.currentTimeMillis()

        for ((canId, hz) in taxas) {
            if (tick % (100L / hz) != 0L) continue
            val payload = when (canId) {
                SinaisDoBaja.ID_MOTOR -> FrameEncoder.encode(
                    8, mapOf(SinaisDoBaja.RPM to e.rpm, SinaisDoBaja.TEMP to e.tempMotor),
                )
                SinaisDoBaja.ID_DINAMICA -> FrameEncoder.encode(
                    8, mapOf(SinaisDoBaja.SPEED to e.velocidade, SinaisDoBaja.GEAR to e.marcha.toDouble()),
                )
                else -> FrameEncoder.encode(
                    8, mapOf(SinaisDoBaja.LAT to e.lat, SinaisDoBaja.LON to e.lon),
                )
            }
            lote += FramePronto(agora, canId, payload.hex())
        }

        // Fecha o lote por tamanho ou por tempo, como o firmware faz (docs/03).
        val cheio = lote.size >= 1000
        val vencido = agora - loteAberto >= 5000
        if (cheio || vencido) {
            nLote++
            val reenviar = reenviarACada > 0 && nLote % reenviarACada == 0
            cliente.enviar(lote, nLote, reenviar)
            totalFrames += lote.size
            lote = mutableListOf()
            loteAberto = agora
        }

        tick++
        Thread.sleep(10)
    }

    if (lote.isNotEmpty()) {
        nLote++
        cliente.enviar(lote, nLote, false)
        totalFrames += lote.size
    }

    val seg = (System.currentTimeMillis() - inicio) / 1000.0
    val reenviados = cliente.enviados - totalFrames
    println()
    println("Fim em ${"%.0f".format(seg)}s -- ${(totalFrames / seg).roundToLong()} frames/s")
    println("  frames unicos gerados : $totalFrames  em $nLote lotes")
    println("  frames enviados       : ${cliente.enviados}" +
        if (reenviados > 0) "  (${reenviados} reenviados com o mesmo batchId)" else "")
    println("  rejeitados pela API   : ${cliente.rejeitados}")
    println("  lotes vistos como duplicados : ${cliente.duplicados}")
    if (reenviados > 0 && cliente.duplicados == 0) {
        println()
        println("  ⚠ ${reenviados} frames entraram DUAS vezes e a API nao percebeu.")
        println("    Esperado ate o checkpoint 2.6: sem persistencia, nao ha como deduplicar.")
        println("    A partir de la esses lotes devem voltar com duplicate: true (ADR-008).")
    }
    if (cliente.erros > 0) {
        println("  ERROS: ${cliente.erros}")
        exitProcess(1)
    }
}

/**
 * O lado cliente do contrato: monta o lote, envia, e se comporta como o firmware
 * deve se comportar diante de cada resposta (docs/08).
 */
private class ClienteIngest(baseUrl: String, val sessao: String, val device: String) {
    private val endpoint = URI.create("$baseUrl/api/v1/ingest")
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    var enviados = 0L; var rejeitados = 0L; var duplicados = 0; var erros = 0

    fun enviar(frames: List<FramePronto>, n: Int, reenviarIgual: Boolean) {
        // O batchId e gerado UMA vez por lote. Retentar precisa usar o MESMO --
        // gerar um novo a cada tentativa destroi a idempotencia (ADR-008).
        val batchId = UUID.randomUUID()
        postComRetentativa(frames, batchId, n)

        if (reenviarIgual) {
            println("   ↻ lote $n reenviado com o MESMO batchId (simula resposta perdida)")
            postComRetentativa(frames, batchId, n)
        }
    }

    private fun postComRetentativa(frames: List<FramePronto>, batchId: UUID, n: Int) {
        var espera = 500L
        repeat(4) { tentativa ->
            val resp = post(corpo(frames, batchId))
            when {
                resp == null -> {
                    println("   ⚠ lote $n: sem resposta. backoff ${espera}ms, MESMO batchId")
                }
                resp.statusCode() in 200..299 -> {
                    relatarSucesso(resp.body(), n, frames.size)
                    return
                }
                // O firmware decide pelo campo `retryable`, nao pelo codigo HTTP.
                resp.body().contains("\"retryable\":true") -> {
                    println("   ⚠ lote $n: HTTP ${resp.statusCode()} retentavel. backoff ${espera}ms")
                }
                else -> {
                    println("   ✖ lote $n: HTTP ${resp.statusCode()} NAO retentavel -- descartando")
                    println("     ${resp.body().take(160)}")
                    erros++
                    return
                }
            }
            if (tentativa < 3) { Thread.sleep(espera); espera *= 2 }
        }
        erros++
        println("   ✖ lote $n: desisti apos 4 tentativas")
    }

    private fun relatarSucesso(body: String, n: Int, enviados: Int) {
        val guardados = extrair(body, "framesStored")
        val recusados = extrair(body, "framesRejected")
        val dup = body.contains("\"duplicate\":true")
        this.enviados += enviados
        rejeitados += recusados
        if (dup) duplicados++
        println(
            "   ✔ lote %3d  %4d frames  guardados=%-5d rejeitados=%-3d%s"
                .format(n, enviados, guardados, recusados, if (dup) "  [duplicate]" else ""),
        )
    }

    private fun extrair(json: String, campo: String): Int =
        Regex("\"$campo\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toInt() ?: 0

    private fun post(corpo: String): HttpResponse<String>? = try {
        http.send(
            HttpRequest.newBuilder(endpoint)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(corpo))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
    } catch (e: Exception) {
        null
    }

    private fun corpo(frames: List<FramePronto>, batchId: UUID): String {
        val lista = frames.joinToString(",") { """{"t":${it.t},"id":${it.id},"data":"${it.data}"}""" }
        return """{"batchId":"$batchId","deviceId":"$device","sessionId":"$sessao","frames":[$lista]}"""
    }
}
