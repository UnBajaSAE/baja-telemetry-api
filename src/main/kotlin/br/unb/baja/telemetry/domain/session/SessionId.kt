package br.unb.baja.telemetry.domain.session

/**
 * Identificador de sessao, gerado pelo DISPOSITIVO (ADR-007).
 *
 * Formato AAAA-MM-DD-slug, legivel de proposito: a lista de sessoes precisa dizer
 * o que cada uma foi sem consultar outra tabela. UUID seria unico mas ilegivel.
 *
 * E value class para o compilador recusar passar um deviceId no lugar de um
 * sessionId -- os dois sao String, e trocar um pelo outro nao daria erro nenhum
 * em runtime.
 */
@JvmInline
value class SessionId private constructor(val value: String) {

    override fun toString(): String = value

    companion object {
        val PATTERN = Regex("^\\d{4}-\\d{2}-\\d{2}-[a-z0-9]+(-[a-z0-9]+)*$")
        const val MAX_LENGTH = 100

        fun isValid(raw: String): Boolean =
            raw.length <= MAX_LENGTH && PATTERN.matches(raw)

        /** Devolve null se o formato nao bate -- quem chama decide o que fazer. */
        fun parseOrNull(raw: String): SessionId? =
            if (isValid(raw)) SessionId(raw) else null
    }
}
