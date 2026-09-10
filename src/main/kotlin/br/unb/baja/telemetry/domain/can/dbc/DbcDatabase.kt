package br.unb.baja.telemetry.domain.can.dbc

import br.unb.baja.telemetry.domain.can.SignalDefinition

/** Uma mensagem CAN: uma linha `BO_` e os `SG_` que moram nela. */
data class CanMessage(
    val canId: Int,
    val name: String,
    val dlc: Int,
    val transmitter: String,
    val signals: List<SignalDefinition>,
) {
    fun signal(nome: String): SignalDefinition? = signals.firstOrNull { it.name == nome }
}

/**
 * O mapa de sinais inteiro, ja indexado por identificador CAN.
 *
 * E a fonte unica de verdade do decodificador (ADR-006), carregada de
 * `contracts/can/unbaja.dbc` na subida da aplicacao -- uma vez, nao por frame.
 */
class DbcDatabase(
    val version: String,
    val messages: List<CanMessage>,
) {
    private val porCanId: Map<Int, CanMessage> = messages.associateBy { it.canId }

    val signalCount: Int = messages.sumOf { it.signals.size }

    fun message(canId: Int): CanMessage? = porCanId[canId]

    fun signal(canId: Int, nome: String): SignalDefinition? = porCanId[canId]?.signal(nome)

    override fun toString(): String =
        "DbcDatabase(version='$version', ${messages.size} mensagens, $signalCount sinais)"
}

/**
 * O parser recusou o arquivo.
 *
 * Sempre carrega a linha e o conteudo: um DBC errado que so diz "erro de sintaxe"
 * manda a pessoa procurar em 60 linhas.
 */
class DbcParseException(mensagem: String) : RuntimeException(mensagem)
