package io.github.aedev.flow.innertube.pages

import io.github.aedev.flow.data.model.RichTextTarget
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCommentsPageTest {
    @Test
    fun `reads the sort menu and count from the comments panel of a watch response`() {
        val response =
            Json.parseToJsonElement(
                """
                {"engagementPanels": [
                  {"engagementPanelSectionListRenderer": {"panelIdentifier": "other", "header": {}}},
                  {"engagementPanelSectionListRenderer": {
                    "panelIdentifier": "engagement-panel-comments-section",
                    "header": {"engagementPanelTitleHeaderRenderer": {
                      "contextualInfo": {"runs": [{"text": "481K"}]},
                      "menu": {"sortFilterSubMenuRenderer": {"subMenuItems": [
                        {"title": "Top", "selected": true, "serviceEndpoint": {"continuationCommand": {"token": "top"}}},
                        {"title": "Newest", "selected": false, "serviceEndpoint": {"continuationCommand": {"token": "newest"}}}
                      ]}}
                    }}
                  }}
                ]}
                """.trimIndent(),
            )

        val header = response.videoCommentsPanelHeader()

        assertEquals(listOf("top", "newest"), header?.sortOptions?.map { it.token })
        assertEquals("481K", header?.totalText)
        assertEquals(481_000L, header?.totalCount)
    }

    @Test
    fun `a watch response with no comments panel has no panel header`() {
        assertNull(Json.parseToJsonElement("""{"engagementPanels": []}""").videoCommentsPanelHeader())
    }

    @Test
    fun `reads the comment section continuation out of a watch response`() {
        val response =
            Json.parseToJsonElement(
                """
                {
                  "contents": {"twoColumnWatchNextResults": {"results": {"results": {"contents": [
                    {"videoPrimaryInfoRenderer": {}},
                    {"itemSectionRenderer": {
                      "sectionIdentifier": "comment-item-section",
                      "contents": [{"continuationItemRenderer": {"continuationEndpoint": {
                        "continuationCommand": {"token": "comments-token"}
                      }}}]
                    }}
                  ]}}}}
                }
                """.trimIndent(),
            )

        assertEquals("comments-token", response.videoCommentsContinuation())
    }

    @Test
    fun `ignores item sections that are not the comment section`() {
        val response =
            Json.parseToJsonElement(
                """
                {
                  "contents": {"twoColumnWatchNextResults": {"results": {"results": {"contents": [
                    {"itemSectionRenderer": {
                      "sectionIdentifier": "related-item-section",
                      "contents": [{"continuationItemRenderer": {"continuationEndpoint": {
                        "continuationCommand": {"token": "related-token"}
                      }}}]
                    }}
                  ]}}}}
                }
                """.trimIndent(),
            )

        assertNull(response.videoCommentsContinuation())
    }

    @Test
    fun `joins threads to their entity payloads and reads the header`() {
        val page = Json.parseToJsonElement(FIRST_PAGE).toVideoCommentsPage(ownVideoId = "fJ9rUzIMcZQ")

        assertEquals(699_091L, page.totalCount)
        assertEquals("699K", page.totalText)
        assertEquals("page-2", page.continuation)
        assertEquals(listOf("Top", "Newest"), page.sortOptions.map { it.title })
        assertEquals(listOf("top-token", "newest-token"), page.sortOptions.map { it.token })
        assertEquals(listOf(true, false), page.sortOptions.map { it.selected })

        val comment = page.comments.single()
        assertEquals("comment-1", comment.id)
        assertEquals("@Queen", comment.author)
        assertEquals("UCiMhD4jzUqG-IgPzUmmytRQ", comment.authorChannelId)
        assertEquals("https://avatar/queen", comment.authorThumbnail)
        assertEquals("Watch 1:57 for the solo", comment.text)
        assertEquals("7 years ago", comment.publishedTime)
        assertEquals("529K", comment.likeCountText)
        assertEquals(529_000, comment.likeCount)
        assertEquals(936, comment.replyCount)
        assertEquals("replies-token", comment.continuationToken)
    }

    @Test
    fun `carries the badges, the pin and the creator heart`() {
        val comment =
            Json
                .parseToJsonElement(FIRST_PAGE)
                .toVideoCommentsPage(ownVideoId = "fJ9rUzIMcZQ")
                .comments
                .single()

        assertTrue(comment.isPinned)
        assertEquals("Pinned by @Queen", comment.pinnedByText)
        assertTrue(comment.isVerified)
        assertTrue(comment.isCreator)
        assertFalse(comment.isArtist)
        assertTrue(comment.isHearted)
        assertEquals("❤ by @Queen", comment.heartedByText)
    }

    @Test
    fun `reads a timestamp span as seconds and a foreign watch link as a video`() {
        val comment =
            Json
                .parseToJsonElement(FIRST_PAGE)
                .toVideoCommentsPage(ownVideoId = "fJ9rUzIMcZQ")
                .comments
                .single()
        val richText = requireNotNull(comment.richText)

        assertTrue(richText.hasTimestamp)
        val timestamp = richText.spans.first { it.target is RichTextTarget.Timestamp }
        assertEquals(117L, (timestamp.target as RichTextTarget.Timestamp).seconds)
        assertEquals("1:57", richText.text.substring(timestamp.start, timestamp.end))

        val otherVideo = richText.spans.first { it.target is RichTextTarget.Video }
        assertEquals("X4VbdwhkE10", (otherVideo.target as RichTextTarget.Video).videoId)
    }

    @Test
    fun `unwraps a redirect link and keeps a plain one`() {
        val comment =
            Json
                .parseToJsonElement(FIRST_PAGE)
                .toVideoCommentsPage(ownVideoId = "fJ9rUzIMcZQ")
                .comments
                .single()
        val url = requireNotNull(comment.richText).spans.first { it.target is RichTextTarget.Url }

        assertEquals("http://www.queenonlinestore.com/", (url.target as RichTextTarget.Url).url)
        assertEquals(
            "https://example.com/plain",
            unwrapRedirectUrl("https://example.com/plain"),
        )
    }

    @Test
    fun `reads a custom emoji attachment`() {
        val comment =
            Json
                .parseToJsonElement(FIRST_PAGE)
                .toVideoCommentsPage(ownVideoId = "fJ9rUzIMcZQ")
                .comments
                .single()
        val emoji = requireNotNull(comment.richText).emojis.single()

        assertEquals("https://emoji/heart.png", emoji.imageUrl)
        assertEquals("❤", emoji.label)
    }

    @Test
    fun `drops spans whose range falls outside the text`() {
        val page =
            Json
                .parseToJsonElement(
                    FIRST_PAGE.replace("\"startIndex\": 6, \"length\": 4", "\"startIndex\": 600, \"length\": 4"),
                ).toVideoCommentsPage(ownVideoId = "fJ9rUzIMcZQ")
        val richText = requireNotNull(page.comments.single().richText)

        assertFalse(richText.hasTimestamp)
    }

    @Test
    fun `reads a replies page with no header`() {
        val page =
            Json
                .parseToJsonElement(
                    """
                    {
                      "onResponseReceivedEndpoints": [{"appendContinuationItemsAction": {"continuationItems": [
                        {"commentViewModel": {"commentViewModel": {
                          "commentKey": "reply-key",
                          "commentId": "reply-1"
                        }}},
                        {"continuationItemRenderer": {"continuationEndpoint": {
                          "continuationCommand": {"token": "more-replies"}
                        }}}
                      ]}}],
                      "frameworkUpdates": {"entityBatchUpdate": {"mutations": [
                        {"entityKey": "reply-key", "payload": {"commentEntityPayload": {
                          "properties": {"commentId": "reply-1", "content": {"content": "Agreed"}, "publishedTime": "1 day ago"},
                          "author": {"displayName": "@fan", "channelId": "UCfan", "avatarThumbnailUrl": "https://avatar/fan"},
                          "toolbar": {"likeCountNotliked": "12", "replyCount": "0"}
                        }}}
                      ]}}
                    }
                    """.trimIndent(),
                ).toCommentRepliesPage(ownVideoId = "fJ9rUzIMcZQ")

        assertEquals("more-replies", page.continuation)
        assertEquals(listOf("reply-1"), page.comments.map { it.id })
        assertEquals("Agreed", page.comments.single().text)
        assertTrue(page.sortOptions.isEmpty())
    }

    @Test
    fun `falls back to the legacy comment renderer`() {
        val page =
            Json
                .parseToJsonElement(
                    """
                    {
                      "onResponseReceivedEndpoints": [{"reloadContinuationItemsCommand": {"continuationItems": [
                        {"commentThreadRenderer": {"comment": {"commentRenderer": {
                          "commentId": "legacy-1",
                          "authorText": {"simpleText": "@old"},
                          "authorThumbnail": {"thumbnails": [{"url": "//avatar/old", "width": 48, "height": 48}]},
                          "contentText": {"runs": [{"text": "Legacy "}, {"text": "comment"}]},
                          "voteCount": {"simpleText": "3.4K"},
                          "publishedTimeText": {"simpleText": "2 years ago"},
                          "replyCount": 5
                        }}}}
                      ]}}]
                    }
                    """.trimIndent(),
                ).toVideoCommentsPage(ownVideoId = "fJ9rUzIMcZQ")

        val comment = page.comments.single()
        assertEquals("legacy-1", comment.id)
        assertEquals("Legacy comment", comment.text)
        assertEquals("https://avatar/old", comment.authorThumbnail)
        assertEquals(3_400, comment.likeCount)
        assertEquals(5, comment.replyCount)
        assertNull(comment.richText)
    }

    private companion object {
        val FIRST_PAGE =
            """
            {
              "onResponseReceivedEndpoints": [
                {"reloadContinuationItemsCommand": {"continuationItems": [
                  {"commentsHeaderRenderer": {
                    "countText": {"runs": [{"text": "699,091"}, {"text": " Comments"}]},
                    "commentsCount": {"runs": [{"text": "699K"}]},
                    "sortMenu": {"sortFilterSubMenuRenderer": {"subMenuItems": [
                      {"title": "Top", "selected": true, "serviceEndpoint": {"continuationCommand": {"token": "top-token"}}},
                      {"title": "Newest", "selected": false, "serviceEndpoint": {"continuationCommand": {"token": "newest-token"}}}
                    ]}}
                  }}
                ]}},
                {"reloadContinuationItemsCommand": {"continuationItems": [
                  {"commentThreadRenderer": {
                    "renderingPriority": "RENDERING_PRIORITY_PINNED_COMMENT",
                    "commentViewModel": {"commentViewModel": {
                      "commentKey": "comment-key",
                      "toolbarStateKey": "toolbar-key",
                      "commentId": "comment-1",
                      "pinnedText": "Pinned by @Queen"
                    }},
                    "replies": {"commentRepliesRenderer": {"contents": [
                      {"continuationItemRenderer": {"continuationEndpoint": {
                        "continuationCommand": {"token": "replies-token"}
                      }}}
                    ]}}
                  }},
                  {"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": "page-2"}}}}
                ]}}
              ],
              "frameworkUpdates": {"entityBatchUpdate": {"mutations": [
                {"entityKey": "comment-key", "payload": {"commentEntityPayload": {
                  "key": "comment-key",
                  "properties": {
                    "commentId": "comment-1",
                    "toolbarStateKey": "toolbar-key",
                    "publishedTime": "7 years ago",
                    "replyLevel": 0,
                    "content": {
                      "content": "Watch 1:57 for the solo",
                      "commandRuns": [
                        {"startIndex": 6, "length": 4, "onTap": {"innertubeCommand": {
                          "watchEndpoint": {"videoId": "fJ9rUzIMcZQ", "startTimeSeconds": 117}
                        }}},
                        {"startIndex": 11, "length": 3, "onTap": {"innertubeCommand": {
                          "watchEndpoint": {"videoId": "X4VbdwhkE10"}
                        }}},
                        {"startIndex": 15, "length": 3, "onTap": {"innertubeCommand": {
                          "urlEndpoint": {"url": "https://www.youtube.com/redirect?event=comment&q=http%3A%2F%2Fwww.queenonlinestore.com%2F&v=fJ9rUzIMcZQ"}
                        }}}
                      ],
                      "attachmentRuns": [
                        {"startIndex": 19, "length": 1, "element": {
                          "type": {"imageType": {"image": {"sources": [{"url": "https://emoji/heart.png", "width": 16, "height": 16}]}}},
                          "properties": {"accessibilityProperties": {"label": "❤"}}
                        }}
                      ]
                    }
                  },
                  "author": {
                    "channelId": "UCiMhD4jzUqG-IgPzUmmytRQ",
                    "displayName": "@Queen",
                    "avatarThumbnailUrl": "https://avatar/queen",
                    "isVerified": true,
                    "isCreator": true,
                    "isArtist": false
                  },
                  "toolbar": {
                    "likeCountNotliked": "529K",
                    "likeCountLiked": "529K",
                    "replyCount": "936",
                    "heartActiveTooltip": "❤ by @Queen"
                  }
                }}},
                {"entityKey": "toolbar-key", "payload": {"engagementToolbarStateEntityPayload": {
                  "key": "toolbar-key",
                  "likeState": "TOOLBAR_LIKE_STATE_INDIFFERENT",
                  "heartState": "TOOLBAR_HEART_STATE_HEARTED"
                }}}
              ]}}
            }
            """.trimIndent()
    }
}
