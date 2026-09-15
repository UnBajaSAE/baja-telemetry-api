package br.unb.baja.telemetry.domain

/**
 * A largura da janela de agregacao pedida na consulta de metricas.
 *
 * So aceita segundos inteiros, e nao menos de um. O agregado continuo e de
 * 1 segundo: pedir uma janela menor NAO TEM RESPOSTA POSSIVEL, e devolver algo
 * assim mesmo daria um numero silenciosamente errado -- o modo de falha que
 * este projeto mais evita. Janela nao multipla de 1 s tambem sai fora, porque
 * reagrupar janelas de 1 s em janelas de 2,5 s desalinha as bordas.
 */
@JvmInline
value class Bucket private constructor(val seconds: Long) {

    /** Formato aceito pelo Postgres em `time_bucket(?::interval, ...)`. */
    val asInterval: String get() = "$seconds seconds"

    override fun toString(): String = when {
        seconds % 3600 == 0L -> "${seconds / 3600}h"
        seconds % 60 == 0L -> "${seconds / 60}m"
        else -> "${seconds}s"
    }

    companion object {
        val PADRAO = Bucket(1)
        const val MINIMO_SEGUNDOS = 1L
        private val FORMATO = Regex("^(\\d+)\\s*(s|m|h)$", RegexOption.IGNORE_CASE)

        /**
         * @return null se o formato nao for reconhecido ou a janela for menor
         *   que 1 s. Quem chama decide o erro HTTP.
         */
        fun parseOrNull(texto: String): Bucket? {
            val m = FORMATO.matchEntire(texto.trim()) ?: return null
            val n = m.groupValues[1].toLongOrNull() ?: return null
            val segundos = when (m.groupValues[2].lowercase()) {
                "s" -> n
                "m" -> n * 60
                else -> n * 3600
            }
            return if (segundos >= MINIMO_SEGUNDOS) Bucket(segundos) else null
        }
    }
}
