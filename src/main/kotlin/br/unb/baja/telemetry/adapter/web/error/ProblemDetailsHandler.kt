package br.unb.baja.telemetry.adapter.web.error

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.net.URI

/** Lote que estourou o limite de frames. Vira 413. */
class BatchTooLargeException(val actual: Int, val max: Int) :
    RuntimeException("lote com $actual frames; o maximo e $max")

/** Sessao inexistente. Vira 404. */
class SessionNotFoundException(val id: String) : RuntimeException("sessao '$id' nao existe")

/** sessionId fora do formato AAAA-MM-DD-slug (ADR-007). Vira 422. */
class InvalidSessionIdException(val raw: String) :
    RuntimeException("sessionId '$raw' fora do formato AAAA-MM-DD-slug")

/**
 * Traduz excecao em resposta de erro no formato Problem Details (RFC 7807),
 * conforme o catalogo do docs/08.
 *
 * O campo `retryable` e extensao nossa e e o mais importante da resposta: e por
 * ele que o firmware decide entre apagar o buffer e guardar para tentar de novo.
 * Sem ele, o ESP32 precisaria embutir em C a tabela de codigos HTTP do servidor.
 */
@RestControllerAdvice
class ProblemDetailsHandler {

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun malformedBody(e: HttpMessageNotReadableException): ProblemDetail =
        problem(
            HttpStatus.BAD_REQUEST,
            "malformed-body",
            "Corpo ilegivel",
            "O JSON nao pode ser lido, ou falta um campo obrigatorio.",
            retryable = false,
        )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalidField(e: MethodArgumentNotValidException): ProblemDetail {
        val campos = e.bindingResult.fieldErrors.joinToString("; ") {
            "${it.field}: ${it.defaultMessage}"
        }
        return problem(
            HttpStatus.BAD_REQUEST,
            "invalid-field",
            "Campo invalido",
            campos.ifBlank { "Requisicao invalida." },
            retryable = false,
        )
    }

    @ExceptionHandler(BatchTooLargeException::class)
    fun batchTooLarge(e: BatchTooLargeException): ProblemDetail =
        problem(
            HttpStatus.CONTENT_TOO_LARGE,
            "batch-too-large",
            "Lote acima do limite",
            "O lote tem ${e.actual} frames; o maximo e ${e.max}. Refatie e reenvie " +
                "-- cada fatia com um batchId novo.",
            retryable = false,
        )

    @ExceptionHandler(SessionNotFoundException::class)
    fun sessionNotFound(e: SessionNotFoundException): ProblemDetail =
        problem(
            HttpStatus.NOT_FOUND,
            "session-not-found",
            "Sessao nao encontrada",
            "Nenhuma sessao com id '${e.id}'. Use GET /api/v1/sessions para ver as existentes.",
            // Consulta, nao ingestao: o `retryable` aqui e para o cliente de
            // leitura. Retentar a mesma URL nao vai fazer a sessao aparecer.
            retryable = false,
        )

    @ExceptionHandler(InvalidSessionIdException::class)
    fun invalidSessionId(e: InvalidSessionIdException): ProblemDetail =
        problem(
            HttpStatus.UNPROCESSABLE_CONTENT,
            "invalid-session-id",
            "sessionId invalido",
            "'${e.raw}' nao segue o formato AAAA-MM-DD-slug (ex.: 2026-08-24-teste-suspensao).",
            retryable = false,
        )

    private fun problem(
        status: HttpStatus,
        slug: String,
        title: String,
        detail: String,
        retryable: Boolean,
    ): ProblemDetail =
        ProblemDetail.forStatusAndDetail(status, detail).apply {
            this.type = URI.create("$BASE_TYPE/$slug")
            this.title = title
            // A decisao do firmware sai daqui, nao do codigo HTTP.
            setProperty("retryable", retryable)
        }

    private companion object {
        const val BASE_TYPE = "https://baja.unb.br/errors"
    }
}
