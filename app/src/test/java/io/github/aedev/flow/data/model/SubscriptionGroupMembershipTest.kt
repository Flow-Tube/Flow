package io.github.aedev.flow.data.model

import io.github.aedev.flow.data.local.entity.SubscriptionGroupEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class SubscriptionGroupMembershipTest {
    private fun group(channelIds: String) = SubscriptionGroupEntity(name = "Music", channelIds = channelIds, sortOrder = 0)

    @Test
    fun addsToAnEmptyGroup() {
        assertEquals("UCa", group("").withMember("UCa", inGroup = true).channelIds)
    }

    @Test
    fun appendsAfterExistingMembers() {
        assertEquals("UCa,UCb", group("UCa").withMember("UCb", inGroup = true).channelIds)
    }

    @Test
    fun addingAMemberTwiceKeepsOneCopy() {
        assertEquals("UCa,UCb", group("UCa,UCb").withMember("UCb", inGroup = true).channelIds)
    }

    @Test
    fun removesAMember() {
        assertEquals("UCa,UCc", group("UCa,UCb,UCc").withMember("UCb", inGroup = false).channelIds)
    }

    @Test
    fun removingTheLastMemberLeavesTheGroupEmpty() {
        assertEquals("", group("UCa").withMember("UCa", inGroup = false).channelIds)
    }

    @Test
    fun removingANonMemberChangesNothing() {
        assertEquals("UCa", group("UCa").withMember("UCz", inGroup = false).channelIds)
    }

    @Test
    fun keepsTheGroupNameAndOrder() {
        val edited = group("UCa").withMember("UCb", inGroup = true)
        assertEquals("Music", edited.name)
        assertEquals(0, edited.sortOrder)
    }
}
