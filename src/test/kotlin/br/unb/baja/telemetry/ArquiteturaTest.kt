package br.unb.baja.telemetry

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * O fiscal do ADR-009.
 *
 * Uma regra de arquitetura que so existe em documento e uma sugestao. Esta quebra
 * o build se alguem -- inclusive o Claude numa sessao futura -- anotar uma classe
 * de dominio com @Component por conveniencia.
 *
 * O motivo e concreto: o decodificador da Fase 2 sera testado com propriedade,
 * milhares de casos por execucao. Com Spring no caminho, cada execucao pagaria
 * bootstrap de contexto -- e teste lento nao fica lento, fica nao executado.
 */
class ArquiteturaTest {

    private val dominio = ClassFileImporter().importPackages("br.unb.baja.telemetry.domain")

    @Test
    fun `o dominio nao depende de framework nenhum`() {
        noClasses().should().dependOnClassesThat()
            .resideInAnyPackage(
                "org.springframework..",
                "jakarta..",
                "tools.jackson..",        // Jackson 3, usado pelo Spring Boot 4
                "com.fasterxml.jackson..", // Jackson 2, caso volte por transitividade
            )
            .`as`("o pacote domain nao pode importar framework (ADR-009)")
            .check(dominio)
    }

    @Test
    fun `o dominio nao depende das camadas de cima`() {
        noClasses().should().dependOnClassesThat()
            .resideInAnyPackage(
                "br.unb.baja.telemetry.adapter..",
                "br.unb.baja.telemetry.application..",
            )
            .`as`("o dominio nao conhece quem o usa")
            .check(dominio)
    }
}
