package com.example.multiscan.core.code128

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Code128C 校验（纯数字 + 偶数长度）单元测试。 */
class Code128CValidatorTest {

    @Test
    fun `valid even length digits`() {
        assertTrue(Code128CValidator.isValid("00"))
        assertTrue(Code128CValidator.isValid("0123456789"))
        assertTrue(Code128CValidator.isValid("99999999999999999999"))
    }

    @Test
    fun `odd length is invalid`() {
        assertFalse(Code128CValidator.isValid("0"))
        assertFalse(Code128CValidator.isValid("123"))
        assertFalse(Code128CValidator.isValid("01234567890"))
    }

    @Test
    fun `non digit characters are invalid`() {
        assertFalse(Code128CValidator.isValid("12a4"))
        assertFalse(Code128CValidator.isValid("12 34"))
        assertFalse(Code128CValidator.isValid("12-34"))
        assertFalse(Code128CValidator.isValid("１２")) // 全角数字
    }

    @Test
    fun `empty is invalid`() {
        assertFalse(Code128CValidator.isValid(""))
        assertNull(Code128CValidator.normalize(""))
        assertNull(Code128CValidator.normalize(null))
    }

    @Test
    fun `normalize returns original for valid code`() {
        assertEquals("012345", Code128CValidator.normalize("012345"))
        assertNull(Code128CValidator.normalize("123"))
    }
}
