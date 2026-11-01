package top.e404.training

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkoutValueInputTest {
    @Test
    fun `十进制加减保留手输入小数且不累计浮点误差`() {
        val first = adjustedValue("82.3", 2.5, 1, BigDecimal.ZERO)
        assertEquals("84.8", first)
        assertEquals("82.3", adjustedValue(first!!, 2.5, -1, BigDecimal.ZERO))
        assertEquals("85", adjustedValue("82.5", 2.5, 1, BigDecimal.ZERO))
    }

    @Test
    fun `重量允许到零但加减不会越过零`() {
        assertEquals("0", adjustedValue("0.5", 0.5, -1, BigDecimal.ZERO))
        assertNull(adjustedValue("0", 0.5, -1, BigDecimal.ZERO))
        assertEquals("0.5", adjustedValue("", 0.5, 1, BigDecimal.ZERO))
    }

    @Test
    fun `次数至少为一次且空白不会预先写入零`() {
        assertNull(adjustedValue("1", 1.0, -1, BigDecimal.ONE))
        assertEquals("1", adjustedValue("", 1.0, 1, BigDecimal.ONE))
        assertEquals("12", adjustedValue("11", 1.0, 1, BigDecimal.ONE))
    }
}
