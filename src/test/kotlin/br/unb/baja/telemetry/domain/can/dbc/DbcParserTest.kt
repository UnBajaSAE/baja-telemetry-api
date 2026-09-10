package br.unb.baja.telemetry.domain.can.dbc

import br.unb.baja.telemetry.domain.can.ByteOrder
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Roda contra o arquivo DE VERDADE, `contracts/can/unbaja.dbc`, copiado para o
 * classpath pelo build. Testar contra um DBC inventado dentro do teste provaria
 * que o parser le o que eu escrevi, nao que ele le o arquivo do projeto.
 */
class DbcParserTest {

    private val arquivoReal: String =
        checkNotNull(javaClass.getResourceAsStream("/can/unbaja.dbc")) {
            "unbaja.dbc nao esta no classpath -- o processResources deveria copiar de contracts/can"
        }.bufferedReader().readText()

    private val db = DbcParser.parse(arquivoReal)

    // ---------------------------------------------------------------- aceite

    @Test
    fun `le as 3 mensagens e os 6 sinais do arquivo do projeto`() {
        assertEquals(3, db.messages.size, "esperava 3 mensagens, veio ${db.messages.map { it.name }}")
        assertEquals(6, db.signalCount)
        assertEquals(
            listOf(0x100, 0x200, 0x300),
            db.messages.map { it.canId }.sorted(),
        )
    }

    @Test
    fun `le a versao e os metadados de cada mensagem`() {
        assertEquals("0.1-PROVISORIO", db.version)
        val motor = assertNotNull(db.message(0x100))
        assertEquals("MOTOR", motor.name)
        assertEquals(8, motor.dlc)
        assertEquals("ECU_MOTOR", motor.transmitter)
    }

    @Test
    fun `le o rpm exatamente como esta escrito no arquivo`() {
        val rpm = assertNotNull(db.signal(0x100, "rpm"))
        assertEquals(7, rpm.startBit)
        assertEquals(16, rpm.bitLength)
        assertEquals(ByteOrder.BIG, rpm.byteOrder, "@0 e big endian, nao little")
        assertEquals(false, rpm.signed)
        assertEquals(0.25, rpm.scale)
        assertEquals(0.0, rpm.offset)
        assertEquals(0.0, rpm.min)
        assertEquals(8000.0, rpm.max)
        assertEquals("rpm", rpm.unit)
    }

    @Test
    fun `distingue big de little endian entre frames diferentes`() {
        assertEquals(ByteOrder.BIG, assertNotNull(db.signal(0x100, "rpm")).byteOrder)
        assertEquals(ByteOrder.LITTLE, assertNotNull(db.signal(0x200, "speed")).byteOrder)
    }

    @Test
    fun `le signed corretamente -- o GPS tem coordenada negativa`() {
        val lat = assertNotNull(db.signal(0x300, "lat"))
        assertTrue(lat.signed, "sem signed, a latitude do hemisferio sul viraria um numero enorme")
        assertEquals(32, lat.bitLength)
        assertEquals(1e-7, lat.scale)
    }

    @Test
    fun `le sinal que nao alinha a byte`() {
        val speed = assertNotNull(db.signal(0x200, "speed"))
        val gear = assertNotNull(db.signal(0x200, "gear"))
        assertEquals(12, speed.bitLength)
        assertEquals(4, gear.bitLength)
        assertEquals(12, gear.startBit)
    }

    @Test
    fun `o bloco NS_ nao e confundido com uso de diretiva`() {
        // O cabecalho do arquivo LISTA os nomes VAL_, BA_ e SG_MUL_VAL_ como
        // simbolos que o formato conhece. Um parser ingenuo falharia ali.
        assertTrue(arquivoReal.contains("\tVAL_"), "o arquivo de teste precisa ter o bloco NS_")
        assertEquals(3, db.messages.size, "o bloco NS_ nao pode derrubar o parse")
    }

    @Test
    fun `comentarios CM_ sao aceitos e ignorados`() {
        assertTrue(arquivoReal.contains("CM_ "), "o arquivo de teste precisa ter comentarios")
        assertEquals(6, db.signalCount)
    }

    // -------------------------------------------------- falhar alto (ADR-006)

    private fun base(extra: String) = """
        VERSION "teste"
        BS_:
        BU_: ECU_A TELEMETRIA

        BO_ 256 MOTOR: 8 ECU_A
         SG_ rpm : 7|16@0+ (0.25,0) [0|8000] "rpm" TELEMETRIA
        $extra
    """.trimIndent()

    @Test
    fun `recusa tabela de enumeracao VAL_`() {
        val e = assertFailsWith<DbcParseException> {
            DbcParser.parse(base("""VAL_ 256 gear 0 "neutro" 1 "primeira" ;"""))
        }
        assertTrue("VAL_" in e.message!! && "nao e suportada" in e.message!!, e.message!!)
    }

    @Test
    fun `recusa atributo customizado BA_`() {
        assertFailsWith<DbcParseException> {
            DbcParser.parse(base("""BA_ "GenMsgCycleTime" BO_ 256 10;"""))
        }
    }

    @Test
    fun `recusa sinal multiplexado`() {
        val e = assertFailsWith<DbcParseException> {
            DbcParser.parse(base(""" SG_ mux m0 : 0|8@1+ (1,0) [0|255] "" TELEMETRIA"""))
        }
        assertTrue("multiplexado" in e.message!!, e.message!!)
    }

    @Test
    fun `recusa identificador estendido de 29 bits`() {
        val e = assertFailsWith<DbcParseException> {
            DbcParser.parse(
                """
                VERSION "teste"
                BO_ 2566844926 ESTENDIDO: 8 ECU_A
                 SG_ x : 7|8@0+ (1,0) [0|255] "" TELEMETRIA
                """.trimIndent(),
            )
        }
        assertTrue("estendido" in e.message!!, e.message!!)
    }

    @Test
    fun `recusa DLC de CAN FD`() {
        val e = assertFailsWith<DbcParseException> {
            DbcParser.parse(
                """
                VERSION "teste"
                BO_ 256 GRANDE: 64 ECU_A
                 SG_ x : 7|8@0+ (1,0) [0|255] "" TELEMETRIA
                """.trimIndent(),
            )
        }
        assertTrue("CAN FD" in e.message!!, e.message!!)
    }

    @Test
    fun `recusa o mesmo canId definido duas vezes`() {
        assertFailsWith<DbcParseException> {
            DbcParser.parse(
                base("") + "\n\nBO_ 256 OUTRA: 8 ECU_A\n SG_ y : 7|8@0+ (1,0) [0|255] \"\" TELEMETRIA",
            )
        }
    }

    @Test
    fun `recusa arquivo sem nenhuma mensagem`() {
        assertFailsWith<DbcParseException> { DbcParser.parse("""VERSION "vazio"""") }
    }

    @Test
    fun `a mensagem de erro diz a linha e mostra o conteudo`() {
        val e = assertFailsWith<DbcParseException> {
            DbcParser.parse(base("""VAL_ 256 gear 0 "neutro" ;"""))
        }
        assertTrue(Regex("linha \\d+").containsMatchIn(e.message!!), "faltou o numero da linha")
        assertTrue("VAL_ 256 gear" in e.message!!, "faltou o conteudo da linha")
    }
}
