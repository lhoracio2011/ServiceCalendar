package app.servicecalendar

import app.servicecalendar.backup.FakeGoogleAuthProvider
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AppContainerTest {
    @BeforeTest
    @AfterTest
    fun reset() = AppContainer.resetForTests()

    @Test
    fun `instance before initialization is an error`() {
        assertFailsWith<IllegalStateException> { AppContainer.instance }
    }

    @Test
    fun `initialize is idempotent and instance returns the same container`() {
        val first = AppContainer.initialize("first.db")
        val second = AppContainer.initialize("other.db")
        assertSame(first, second)
        assertSame(first, AppContainer.instance)
        assertSame(first.dataStore, AppContainer.instance.dataStore)
    }

    @Test
    fun `backup coordinator without an auth provider is a clear error`() {
        val container = AppContainer.initialize("no-auth.db")
        assertFalse(container.hasGoogleAuthProvider)
        val error = assertFailsWith<IllegalStateException> { container.backupCoordinator }
        assertTrue("setGoogleAuthProvider" in error.message.orEmpty())
    }

    @Test
    fun `auth provider is set once and the coordinator is a single lazy instance`() {
        val container = AppContainer.initialize("with-auth.db")
        val provider = FakeGoogleAuthProvider()
        container.setGoogleAuthProvider(provider)
        container.setGoogleAuthProvider(provider)

        assertFailsWith<IllegalStateException> { container.setGoogleAuthProvider(FakeGoogleAuthProvider()) }
        assertTrue(container.hasGoogleAuthProvider)
        assertSame(container.backupCoordinator, container.backupCoordinator)
        assertSame(container.driveBackupClient, container.driveBackupClient)
    }
}
