package com.samosaai.masala.skills

import kotlin.test.Test
import kotlin.test.assertEquals

class CommunitySkillStoreTest {
    @Test
    fun `safeFilename keeps ids inside the skills directory`() {
        assertEquals("good_id", CommunitySkillStore.safeFilename("good_id"))
        assertEquals("etcpasswd", CommunitySkillStore.safeFilename("../../etc/passwd"))
        assertEquals("skill", CommunitySkillStore.safeFilename("../.."))
        assertEquals(64, CommunitySkillStore.safeFilename("a".repeat(100)).length)
    }
}
