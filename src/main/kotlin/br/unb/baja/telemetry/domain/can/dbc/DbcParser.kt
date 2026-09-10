package br.unb.baja.telemetry.domain.can.dbc

import br.unb.baja.telemetry.domain.can.ByteOrder
import br.unb.baja.telemetry.domain.can.CanFrame
import br.unb.baja.telemetry.domain.can.SignalDefinition

/**
 * Le o subconjunto `BO_` + `SG_` do formato DBC (ADR-006).
 *
 * ## Por que um parser proprio
 *
 * O subconjunto de que precisamos sao duas diretivas. Trocar ~150 linhas de
 * codigo testavel por uma dependencia opaca e mau negocio quando o codigo em
 * questao alimenta o coracao do sistema.
 *
 * ## Por que ele FALHA em vez de ignorar
 *
 * Pular uma linha desconhecida em silencio perde um sinal inteiro sem ninguem
 * notar -- e o modo de falha mais caro deste projeto. Diretiva nao suportada
 * derruba a subida da aplicacao, com a linha e o conteudo na mensagem.
 *
 * Fora de escopo desta versao, listado em docs/05 §5.2: `VAL_`, multiplexacao,
 * `BA_`, CAN FD e identificadores estendidos de 29 bits.
 */
object DbcParser {

    private val BO = Regex(
        """^BO_\s+(\d+)\s+([A-Za-z_]\w*)\s*:\s*(\d+)\s+([A-Za-z_]\w*)\s*$""",
    )

    private val SG = Regex(
        """^\s*SG_\s+([A-Za-z_]\w*)\s*(?<mux>[Mm]\d*)?\s*:\s*""" +
            """(\d+)\|(\d+)@([01])([+-])\s*""" +
            """\(\s*([-+\d.eE]+)\s*,\s*([-+\d.eE]+)\s*\)\s*""" +
            """\[\s*([-+\d.eE]*)\s*\|\s*([-+\d.eE]*)\s*]\s*""" +
            """"([^"]*)"\s*(.*)$""",
    )

    /** Diretivas que o formato tem e que esta versao NAO entende. */
    private val NAO_SUPORTADAS = listOf(
        "VAL_" to "tabelas de enumeracao",
        "VAL_TABLE_" to "tabelas de enumeracao",
        "SG_MUL_VAL_" to "multiplexacao",
        "BA_" to "atributos customizados",
        "EV_" to "variaveis de ambiente",
        "SIG_VALTYPE_" to "tipo de valor de sinal (float/double)",
        "BO_TX_BU_" to "transmissores adicionais",
    )

    /** Identificador acima disto no DBC significa CAN estendido (29 bits). */
    private const val FLAG_ESTENDIDO = 0x8000_0000L

    fun parse(texto: String): DbcDatabase {
        val linhas = texto.lines()
        var versao = ""
        val mensagens = mutableListOf<CanMessage>()
        var atual: MutableList<SignalDefinition>? = null
        var cabecalho: Triple<Int, String, Int>? = null
        var transmissor = ""

        var i = 0
        while (i < linhas.size) {
            val bruta = linhas[i]
            val linha = bruta.trim()
            val n = i + 1
            i++

            if (linha.isEmpty()) continue

            // O bloco NS_ LISTA os nomes das diretivas que o formato conhece --
            // inclusive VAL_ e BA_. Falhar aqui seria recusar o proprio cabecalho.
            if (linha.startsWith("NS_")) {
                while (i < linhas.size && (linhas[i].isBlank() || linhas[i].first().isWhitespace())) i++
                continue
            }

            when {
                linha.startsWith("VERSION") -> {
                    versao = Regex(""""([^"]*)"""").find(linha)?.groupValues?.get(1) ?: ""
                    continue
                }
                // Comentarios sao aceitos e ignorados. Podem ocupar varias linhas.
                linha.startsWith("CM_") -> {
                    while (i < linhas.size && !linhas[i - 1].trimEnd().endsWith(";")) i++
                    continue
                }
                linha.startsWith("BS_") || linha.startsWith("BU_") -> continue
            }

            NAO_SUPORTADAS.firstOrNull { (dir, _) -> linha.startsWith(dir) }?.let { (dir, oque) ->
                throw DbcParseException(
                    "linha $n: diretiva '$dir' ($oque) nao e suportada nesta versao do parser.\n" +
                        "  $linha\n" +
                        "  O parser recusa em vez de ignorar: pular uma linha perderia um sinal " +
                        "inteiro sem ninguem notar (ADR-006).\n" +
                        "  O escopo do parser v1 esta em docs/05 §5.2.",
                )
            }

            BO.matchEntire(linha)?.let { m ->
                cabecalho?.let { (id, nome, dlc) ->
                    mensagens += CanMessage(id, nome, dlc, transmissor, atual.orEmpty())
                }
                val idBruto = m.groupValues[1].toLong()
                if (idBruto >= FLAG_ESTENDIDO) {
                    throw DbcParseException(
                        "linha $n: identificador estendido de 29 bits nao e suportado.\n  $linha\n" +
                            "  O projeto usa CAN 2.0A (11 bits) -- ver docs/01.",
                    )
                }
                val canId = idBruto.toInt()
                if (canId > CanFrame.MAX_CAN_ID) {
                    throw DbcParseException(
                        "linha $n: canId $canId acima do maximo de 11 bits (${CanFrame.MAX_CAN_ID}).\n  $linha",
                    )
                }
                val dlc = m.groupValues[3].toInt()
                if (dlc > CanFrame.MAX_PAYLOAD_BYTES) {
                    throw DbcParseException(
                        "linha $n: DLC $dlc acima de ${CanFrame.MAX_PAYLOAD_BYTES} bytes -- " +
                            "isso e CAN FD, fora de escopo.\n  $linha",
                    )
                }
                cabecalho = Triple(canId, m.groupValues[2], dlc)
                transmissor = m.groupValues[4]
                atual = mutableListOf()
                return@let
            } ?: run {
                if (linha.startsWith("BO_")) {
                    throw DbcParseException("linha $n: linha BO_ malformada.\n  $linha")
                }
            }

            if (linha.startsWith("SG_")) {
                val m = SG.matchEntire(bruta)
                    ?: throw DbcParseException("linha $n: linha SG_ malformada.\n  $linha")
                if (m.groups["mux"] != null) {
                    throw DbcParseException(
                        "linha $n: sinal multiplexado nao e suportado nesta versao.\n  $linha\n" +
                            "  Ver docs/05 §5.2.",
                    )
                }
                val alvo = atual ?: throw DbcParseException(
                    "linha $n: SG_ fora de qualquer BO_.\n  $linha",
                )
                alvo += sinalDe(m, n, linha)
            }
        }

        cabecalho?.let { (id, nome, dlc) ->
            mensagens += CanMessage(id, nome, dlc, transmissor, atual.orEmpty())
        }

        if (mensagens.isEmpty()) throw DbcParseException("nenhuma mensagem BO_ encontrada no arquivo")
        mensagens.groupBy { it.canId }.filterValues { it.size > 1 }.keys.firstOrNull()?.let {
            throw DbcParseException("canId 0x${it.toString(16).uppercase()} definido mais de uma vez")
        }
        return DbcDatabase(versao, mensagens)
    }

    private fun sinalDe(m: MatchResult, n: Int, linha: String): SignalDefinition {
        val g = m.groupValues
        val nome = g[1]
        val largura = g[4].toInt()
        val startBit = g[3].toInt()

        // @0 e big endian (Motorola) e @1 e little endian (Intel). A numeracao e
        // invertida em relacao a intuicao, e trocar os dois nao gera erro --
        // gera numero plausivel e errado (docs/05 §4.2).
        val ordem = if (g[5] == "0") ByteOrder.BIG else ByteOrder.LITTLE
        val comSinal = g[6] == "-"

        if (startBit !in 0..63) {
            throw DbcParseException("linha $n: bit inicial $startBit fora de 0..63.\n  $linha")
        }
        if (largura !in 1..64) {
            throw DbcParseException("linha $n: largura $largura fora de 1..64.\n  $linha")
        }

        return try {
            SignalDefinition(
                name = nome,
                startBit = startBit,
                bitLength = largura,
                byteOrder = ordem,
                signed = comSinal,
                scale = g[7].toDouble(),
                offset = g[8].toDouble(),
                min = g[9].toDoubleOrNull() ?: Double.NEGATIVE_INFINITY,
                max = g[10].toDoubleOrNull() ?: Double.POSITIVE_INFINITY,
                unit = g[11],
            )
        } catch (e: IllegalArgumentException) {
            throw DbcParseException("linha $n: ${e.message}\n  $linha")
        }
    }
}
