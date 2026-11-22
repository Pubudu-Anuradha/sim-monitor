package app.simmonitor.probe

import org.junit.Assert.assertEquals
import org.junit.Test

class CarrierTest {
    @Test fun byMccMnc() {
        assertEquals(Carrier.MOBITEL, Carrier.detect("413", "01", ""))
        assertEquals(Carrier.DIALOG, Carrier.detect("413", "02", ""))
        assertEquals(Carrier.DIALOG, Carrier.detect("413", "05", "Airtel"))
        assertEquals(Carrier.HUTCH, Carrier.detect("413", "03", ""))
        assertEquals(Carrier.HUTCH, Carrier.detect("413", "08", ""))
        assertEquals(Carrier.MOBITEL, Carrier.detect("413", "1", "")) // pre-API-29 int form
    }

    @Test fun byNameFallback() {
        assertEquals(Carrier.DIALOG, Carrier.detect(null, null, "Dialog 4G"))
        assertEquals(Carrier.MOBITEL, Carrier.detect(null, null, "SLT-MOBITEL"))
        assertEquals(Carrier.HUTCH, Carrier.detect(null, null, "Hutch"))
        assertEquals(Carrier.UNKNOWN, Carrier.detect("310", "260", "T-Mobile"))
    }
}

class CarrierSendersTest {
    @Test fun allowsCarrierSenders() {
        listOf("HUTCH", "5555", "678", "Mobitel", "SLT-Mobitel", "Dialog", "160").forEach {
            assertEquals(it, true, CarrierSenders.isAllowed(it))
        }
    }

    @Test fun rejectsOthers() {
        listOf("MyBank", "+10000000000", "BOC", "Google", "", null).forEach {
            assertEquals(it.toString(), false, CarrierSenders.isAllowed(it))
        }
    }
}
