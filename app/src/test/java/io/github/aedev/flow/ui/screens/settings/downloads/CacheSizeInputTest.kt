package io.github.aedev.flow.ui.screens.settings.downloads

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class CacheSizeInputTest {
    @Test
    fun `whole and fractional sizes become megabytes in the chosen unit`() {
        assertThat(CacheSizeInput.parse("750", CacheSizeUnit.MB)).isEqualTo(750)
        assertThat(CacheSizeInput.parse("1.5", CacheSizeUnit.GB)).isEqualTo(1536)
        assertThat(CacheSizeInput.parse("1,5", CacheSizeUnit.GB)).isEqualTo(1536)
        assertThat(CacheSizeInput.parse(" 3 ", CacheSizeUnit.GB)).isEqualTo(3072)
    }

    @Test
    fun `sizes outside 50 MB to 50 GB and non-numbers are refused`() {
        assertThat(CacheSizeInput.parse("49", CacheSizeUnit.MB)).isNull()
        assertThat(CacheSizeInput.parse("50", CacheSizeUnit.MB)).isEqualTo(50)
        assertThat(CacheSizeInput.parse("50", CacheSizeUnit.GB)).isEqualTo(51200)
        assertThat(CacheSizeInput.parse("51", CacheSizeUnit.GB)).isNull()
        assertThat(CacheSizeInput.parse("", CacheSizeUnit.MB)).isNull()
        assertThat(CacheSizeInput.parse("abc", CacheSizeUnit.MB)).isNull()
        assertThat(CacheSizeInput.parse("NaN", CacheSizeUnit.GB)).isNull()
        assertThat(CacheSizeInput.parse("-200", CacheSizeUnit.MB)).isNull()
    }

    @Test
    fun `a stored size reads in MB below a gigabyte and in GB from there`() {
        assertThat(CacheSizeInput.unitFor(750)).isEqualTo(CacheSizeUnit.MB)
        assertThat(CacheSizeInput.unitFor(1024)).isEqualTo(CacheSizeUnit.GB)
        assertThat(CacheSizeInput.format(750, CacheSizeUnit.MB, Locale.US)).isEqualTo("750")
        assertThat(CacheSizeInput.format(1536, CacheSizeUnit.GB, Locale.US)).isEqualTo("1.5")
        assertThat(CacheSizeInput.format(1536, CacheSizeUnit.GB, Locale.GERMANY)).isEqualTo("1,5")
        assertThat(CacheSizeInput.format(3072, CacheSizeUnit.GB, Locale.US)).isEqualTo("3")
    }
}
