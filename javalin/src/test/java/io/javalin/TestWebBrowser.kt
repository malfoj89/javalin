/*
 * Javalin - https://javalin.io
 * Copyright 2017 David Åse
 * Licensed under Apache 2.0: https://github.com/tipsy/javalin/blob/master/LICENSE
 */

package io.javalin

import io.github.bonigarcia.wdm.WebDriverManager
import io.javalin.http.Header
import io.javalin.http.util.SeekableWriter.chunkSize
import io.javalin.testing.TestEnvironment
import io.javalin.testing.TestUtil.captureStdOut
import io.javalin.testing.TestUtil
import org.assertj.core.api.AssertionsForClassTypes.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.openqa.selenium.chrome.ChromeDriver
import org.openqa.selenium.chrome.ChromeOptions
import java.io.File
import kotlin.math.ceil

class TestWebBrowser {

    companion object {

        lateinit var driver: ChromeDriver

        @BeforeAll
        @JvmStatic
        fun setupClass() {
            assumeTrue(TestEnvironment.isNotCiServer) // we are seeing some issues with browsers on CI
            WebDriverManager.chromedriver().setup()
            driver = ChromeDriver(ChromeOptions().apply {
                addArguments("--no-sandbox")
                addArguments("--headless")
                addArguments("--disable-gpu")
            })
        }

        @AfterAll
        @JvmStatic
        fun teardownClass() {
            if (Companion::driver.isInitialized) {
                driver.quit()
            }
        }
    }

    @Test
    fun `hello world works in chrome`() = TestUtil.test { app, http ->
        app.get("/hello") { it.result("Hello, Selenium!") }
        driver.get(http.origin + "/hello")
        assertThat(driver.pageSource).contains("Hello, Selenium")
    }

    @Test
    fun `brotli works in chrome`() {
        TestUtil.runAndCaptureLogs {
            val payload = "Hello, Selenium!".repeat(150)
            val app = Javalin.create {
                it.compression.brotliOnly()
                it.plugins.enableDevLogging()
            }.start(0)
            app.get("/hello") { it.result(payload) }
            val logResult = captureStdOut {
                driver.get("http://localhost:" + app.port() + "/hello")
            }
            assertThat(driver.pageSource).contains(payload)
            assertThat(logResult).contains("Content-Encoding=br")
            assertThat(logResult).contains("Body is brotlied (${payload.length} bytes, not logged)")
            app.stop()
        }
    }

    @Test
    fun `seeking works in chrome`() {
        chunkSize = 30000
        val file = File("src/test/resources/upload-test/sound.mp3")
        val expectedChunkCount = ceil(file.inputStream().available() / chunkSize.toDouble()).toInt()
        var chunkCount = 0
        val requestLoggerApp = Javalin.create {
            it.requestLogger.http { ctx, ms ->
                if (ctx.req().getHeader(Header.RANGE) == null) return@http
                chunkCount++
                // println("Req: " + ctx.req.getHeader(Header.RANGE))
                // println("Res: " + ctx.res().getHeader(Header.CONTENT_RANGE))
            }
        }
        TestUtil.test(requestLoggerApp) { app, http ->
            app.get("/file") { it.writeSeekableStream(file.inputStream(), "audio/mpeg") }
            app.get("/audio-player") { it.html("""<audio src="/file"></audio>""") }
            driver.get(http.origin + "/audio-player")
            Thread.sleep(100) // so the logger has a chance to run
            assertThat(chunkCount).isEqualTo(expectedChunkCount)
            chunkSize = 128000
        }
    }

}
