package com.vexeq.compat

import org.junit.Assert.assertEquals
import org.junit.Test

class CompatibilityPolicyTest {
    @Test fun registeredVendorEqualizerIsOnlyPartialGlobalSupport() {
        assertEquals(CompatibilityStatus.PARTIALLY_SUPPORTED, CompatibilityPolicy.globalStatus(true))
    }
    @Test fun missingEqualizerDescriptorIsUnsupported() {
        assertEquals(CompatibilityStatus.UNSUPPORTED, CompatibilityPolicy.globalStatus(false))
    }
}
