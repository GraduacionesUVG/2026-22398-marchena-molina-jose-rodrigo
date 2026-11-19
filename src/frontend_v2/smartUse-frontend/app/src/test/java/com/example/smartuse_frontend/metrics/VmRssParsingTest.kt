package com.example.smartuse_frontend.metrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VmRssParsingTest {

    @Test
    fun `reads VmRSS in kB from a real proc status`() {
        val status = """
            Name:	e.smartuse_deployment
            Umask:	0077
            State:	S (sleeping)
            VmPeak:	15012344 kB
            VmSize:	14839096 kB
            VmHWM:	  132544 kB
            VmRSS:	   98304 kB
            RssAnon:	   30112 kB
            Threads:	34
        """.trimIndent()
        assertEquals(98_304L, parseVmRssKb(status))
    }

    @Test
    fun `no VmRSS line is NULL`() {
        assertNull(parseVmRssKb("Name:\tx\nVmSize:\t100 kB\n"))
    }

    @Test
    fun `junk is NULL`() {
        assertNull(parseVmRssKb("VmRSS:\tlots kB\n"))
        assertNull(parseVmRssKb(""))
    }
}
