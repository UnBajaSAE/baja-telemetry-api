package br.unb.baja.telemetry.domain.can

/**
 * O conteudo de `contracts/can/unbaja.dbc`, escrito a mao.
 *
 * ⚠️ TEMPORARIO. No checkpoint 2.3 o parser de DBC passa a produzir estes
 * objetos lendo o arquivo, e este codigo sai. Ele existe porque o gerador
 * sintetico (ADR-005) vem antes do parser no plano, e precisa codificar frames.
 *
 * Enquanto durar, a verdade esta em DOIS lugares -- exatamente o que o ADR-006
 * quer evitar. O `SinaisDoBajaTest` compara byte a byte contra a saida do
 * `cantools` lendo o arquivo de verdade, entao a divergencia e detectada.
 */
object SinaisDoBaja {

    const val ID_MOTOR = 0x100
    const val ID_DINAMICA = 0x200
    const val ID_GPS = 0x300

    // BO_ 256 MOTOR -- alinhado a byte, unsigned, big endian
    val RPM = SignalDefinition("rpm", 7, 16, ByteOrder.BIG, false, 0.25, 0.0, 0.0, 8000.0, "rpm")
    val TEMP = SignalDefinition("temp", 23, 8, ByteOrder.BIG, false, 1.0, -40.0, -40.0, 215.0, "degC")

    // BO_ 512 DINAMICA -- sinais que cruzam fronteira de byte, little endian
    val SPEED = SignalDefinition("speed", 0, 12, ByteOrder.LITTLE, false, 0.1, 0.0, 0.0, 150.0, "km/h")
    val GEAR = SignalDefinition("gear", 12, 4, ByteOrder.LITTLE, false, 1.0, 0.0, 0.0, 5.0, "")

    // BO_ 768 GPS -- signed em complemento de dois, escala fracionaria
    val LAT = SignalDefinition("lat", 7, 32, ByteOrder.BIG, true, 1e-7, 0.0, -90.0, 90.0, "deg")
    val LON = SignalDefinition("lon", 39, 32, ByteOrder.BIG, true, 1e-7, 0.0, -180.0, 180.0, "deg")
}
