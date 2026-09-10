plugins {
	kotlin("jvm") version "2.4.10"
	kotlin("plugin.spring") version "2.4.10"
	id("org.springframework.boot") version "4.1.1"
	id("io.spring.dependency-management") version "1.1.7"
}

group = "br.unb.baja"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	// Bean Validation: valida o LOTE inteiro (o frame individual e validado no
	// servico, porque pelo ADR-010 um frame ruim nao derruba o lote).
	implementation("org.springframework.boot:spring-boot-starter-validation")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")
	implementation("org.springframework.boot:spring-boot-starter-jdbc")
	// Schema versionado em git, nunca ddl-auto.
	// No Spring Boot 3 bastava o flyway-core no classpath; na linha 4 a
	// autoconfiguracao foi para um modulo proprio, entregue por este starter.
	// E desde o Flyway 10 o suporte a cada banco vem separado do core.
	implementation("org.springframework.boot:spring-boot-starter-flyway")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("tools.jackson.module:jackson-module-kotlin")
	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	// Faz valer a regra do ADR-009: o pacote domain nao importa framework.
	testImplementation("com.tngtech.archunit:archunit-junit5:1.5.0")
	// Postgres de verdade no teste, nunca banco em memoria (ADR-003).
	// Na linha 2.x do Testcontainers os modulos ganharam prefixo:
	// e `testcontainers-postgresql`, nao `postgresql` como na 1.x.
	testImplementation("org.springframework.boot:spring-boot-testcontainers")
	testImplementation("org.testcontainers:testcontainers-postgresql")
	testImplementation("org.testcontainers:testcontainers-junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

/**
 * O gerador sintetico (ADR-005) e uma FERRAMENTA, nao parte da API: ele se passa
 * pelo ESP32. Fica num source set proprio para nao entrar no jar de producao,
 * mas enxerga o dominio -- e assim ele codifica os frames com as mesmas
 * definicoes de sinal que o decodificador vai usar, sem duplicar a regra.
 */
sourceSets {
	create("gerador") {
		compileClasspath += sourceSets["main"].output
		runtimeClasspath += sourceSets["main"].output
	}
}

configurations.getByName("geradorImplementation") {
	extendsFrom(configurations.implementation.get())
}

tasks.register<JavaExec>("gerador") {
	group = "application"
	description = "Alimenta o /ingest com telemetria sintetica, sem o carro presente (ADR-005)"
	classpath = sourceSets["gerador"].runtimeClasspath
	mainClass.set("br.unb.baja.telemetry.gerador.GeradorKt")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict")
	}
}

/**
 * O DBC mora em contracts/ porque e contrato compartilhado entre firmware, API e
 * ferramentas de bancada -- nao e recurso da API (docs/05 §2). O build copia
 * para o classpath para a aplicacao nao depender do diretorio de execucao.
 */
tasks.named<ProcessResources>("processResources") {
	from("contracts/can") { into("can") }
}

tasks.withType<Test> {
	useJUnitPlatform()
}
