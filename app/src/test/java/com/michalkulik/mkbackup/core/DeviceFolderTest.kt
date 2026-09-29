package com.michalkulik.mkbackup.core

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceFolderTest {

    @Test
    fun `keeps the device name as it is`() {
        assertEquals("Galaxy S23+", sanitizeDeviceFolder("Galaxy S23+"))
        assertEquals("Telefon Michala", sanitizeDeviceFolder("Telefon Michala"))
        assertEquals("Pixel 8 Pro", sanitizeDeviceFolder("Pixel 8 Pro"))
    }

    @Test
    fun `collapses whitespace and trims the edges`() {
        assertEquals("Galaxy S23", sanitizeDeviceFolder("   Galaxy \t S23  "))
        assertEquals("Galaxy S23", sanitizeDeviceFolder("Galaxy\nS23"))
    }

    @Test
    fun `replaces characters that would break a path or an url`() {
        assertEquals("a b c", sanitizeDeviceFolder("a/b\\c"))
        assertEquals("Galaxy S23", sanitizeDeviceFolder("\"Galaxy\" <S23>"))
        assertEquals("a b", sanitizeDeviceFolder("a|b"))
        assertEquals("a b c d", sanitizeDeviceFolder("a:b*c?d"))
    }

    @Test
    fun `refuses names that would escape the sets directory`() {
        assertEquals("", sanitizeDeviceFolder(".."))
        assertEquals("", sanitizeDeviceFolder("."))
        assertEquals("", sanitizeDeviceFolder("   .   "))
        assertEquals("", sanitizeDeviceFolder(""))
        assertEquals("", sanitizeDeviceFolder("   "))
        assertEquals("", sanitizeDeviceFolder("///"))
    }

    @Test
    fun `cuts the name to what the server accepts`() {
        assertEquals(MAX_FOLDER_LENGTH, sanitizeDeviceFolder("x".repeat(400)).length)
        assertEquals("x".repeat(MAX_FOLDER_LENGTH), sanitizeDeviceFolder("x".repeat(MAX_FOLDER_LENGTH)))
    }
}
