package io.sentry.spring

import io.sentry.Sentry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SentryTaskDecoratorTest {
    private val dsn = "http://key@localhost/proj"
    private lateinit var executor: ExecutorService

    @BeforeTest
    fun beforeTest() {
        executor = Executors.newSingleThreadExecutor()
    }

    @AfterTest
    fun afterTest() {
        Sentry.close()
        executor.shutdown()
    }

    @Test
    fun `hub is reset to its state within the thread after decoration is done`() {
        Sentry.init {
            it.dsn = dsn
        }

        val sut = SentryTaskDecorator()

        val mainHub = Sentry.getCurrentHub()
        val threadedHub = Sentry.getCurrentHub().clone()

        executor.submit {
            Sentry.setCurrentHub(threadedHub)
        }.get()

        assertEquals(mainHub, Sentry.getCurrentHub())

        val callableFuture =
            executor.submit(
                sut.decorate {
                    assertNotEquals(mainHub, Sentry.getCurrentHub())
                    assertNotEquals(threadedHub, Sentry.getCurrentHub())
                }
            )

        callableFuture.get()

        executor.submit {
            assertNotEquals(mainHub, Sentry.getCurrentHub())
            assertEquals(threadedHub, Sentry.getCurrentHub())
        }.get()
    }
}
