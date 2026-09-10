package br.unb.baja.telemetry.gerador

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/** O estado fisico do carro num instante. */
data class Estado(
    val rpm: Double,
    val tempMotor: Double,
    val velocidade: Double,
    val marcha: Int,
    val lat: Double,
    val lon: Double,
)

/**
 * Modelo simples de um Baja dando voltas.
 *
 * Nao pretende ser simulacao de dinamica veicular -- pretende produzir curvas
 * que se PARECEM com telemetria real, para que um grafico gerado por ele engane
 * o olho e sirva de demonstracao. Se o dado fosse aleatorio puro, qualquer bug
 * de decodificacao passaria despercebido: ruido errado tambem parece ruido.
 */
class Carro(
    private val voltaSegundos: Double = 48.0,
    seed: Int = 42,
) {
    private val rnd = Random(seed)

    // Traçado ficticio nas coordenadas de Brasilia.
    private val latCentro = -15.7942
    private val lonCentro = -47.8822
    private val raio = 0.0012

    fun estadoEm(segundos: Double): Estado {
        val fase = (segundos % voltaSegundos) / voltaSegundos

        // Acelerador: duas harmonicas dao retas longas e curvas curtas,
        // em vez da senoide unica que pareceria artificial.
        val acelerador = (0.52 + 0.34 * sin(2 * PI * fase) + 0.14 * sin(6 * PI * fase))
            .coerceIn(0.05, 1.0)

        val rpm = (1150 + acelerador * 2750 + rnd.nextDouble(-45.0, 45.0))
            .coerceIn(900.0, 4200.0)

        val velocidade = (acelerador * 52 + rnd.nextDouble(-1.5, 1.5)).coerceIn(0.0, 60.0)

        val marcha = when {
            velocidade < 12 -> 1
            velocidade < 24 -> 2
            velocidade < 38 -> 3
            else -> 4
        }

        // Motor frio esquenta rapido e estabiliza; carga alta aquece mais.
        val aquecimento = 1 - exp(-segundos / 210.0)
        val tempMotor = (38 + 54 * aquecimento + acelerador * 7 + rnd.nextDouble(-0.8, 0.8))
            .coerceIn(30.0, 118.0)

        // Oval: mais comprido que largo, como um traçado de verdade.
        val lat = latCentro + raio * sin(2 * PI * fase)
        val lon = lonCentro + raio * 1.7 * cos(2 * PI * fase)

        return Estado(rpm, tempMotor, velocidade, marcha, lat, lon)
    }
}
