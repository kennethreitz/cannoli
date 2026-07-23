package dev.cannoli.scorza.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StarboardTargetTest {

    @Test fun `starboard target round trips launch metadata`() {
        val target = StarboardTarget(
            StarboardTarget.STARBOARD_PACKAGE,
            "space.cadet.pinball.zip",
            "Space Cadet Pinball.sh",
            "3D Pinball Space Cadet",
        )

        assertEquals(target, StarboardTarget.decode(target.encode()))
    }

    @Test fun `ordinary package name is not a starboard target`() {
        assertNull(StarboardTarget.decode(StarboardTarget.STARBOARD_PACKAGE))
    }

    @Test fun `unsafe launcher path is rejected`() {
        assertNull(
            StarboardTarget.decode(
                "cannoli-starboard://org.force9.starboard/vvvvvv.zip" +
                    "?launcher=../VVVVVV.sh&title=VVVVVV"
            )
        )
    }
}
