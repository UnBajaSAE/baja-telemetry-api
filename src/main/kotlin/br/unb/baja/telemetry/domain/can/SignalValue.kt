package br.unb.baja.telemetry.domain.can

/**
 * Um sinal ja traduzido para grandeza fisica.
 *
 * `valid = false` NAO significa "descartar". Sensor desconectado manda 0xFF em
 * tudo, o que sai como 16.383 rpm -- o ponto e gravado e marcado, porque saber
 * que o sensor caiu as 09:52 tambem e informacao. Um buraco no grafico nao
 * distingue "sensor morreu" de "carro parado".
 */
data class SignalValue(
    val name: String,
    val value: Double,
    val valid: Boolean,
    val unit: String,
    /** Por que foi marcado invalido. Nulo quando `valid`. */
    val invalidReason: String? = null,
) {
    init {
        require(valid == (invalidReason == null)) {
            "$name: valid=$valid e incompativel com invalidReason=$invalidReason"
        }
    }
}
