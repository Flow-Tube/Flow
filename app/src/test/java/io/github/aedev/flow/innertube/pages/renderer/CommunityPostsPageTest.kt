package io.github.aedev.flow.innertube.pages.renderer

import io.github.aedev.flow.data.model.RichTextTarget
import io.github.aedev.flow.innertube.pages.youtubeText
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommunityPostsPageTest {
    @Test
    fun `reads every modern entity text shape`() {
        assertEquals(
            "Primitive text",
            Json.parseToJsonElement("\"Primitive text\"").youtubeText(),
        )
        assertEquals(
            "Content text",
            Json.parseToJsonElement("""{"content":"Content text"}""").youtubeText(),
        )
        assertEquals(
            "Mixed runs",
            Json
                .parseToJsonElement(
                    """{"runs":[{"text":"Mixed "},{"content":"runs"}]}""",
                ).youtubeText(),
        )
    }

    @Test
    fun `parses community post and sign-in wrapped comment endpoint`() {
        val response =
            Json.parseToJsonElement(
                """
                {
                  "contents": [{
                    "richItemRenderer": {
                      "content": {
                        "backstagePostThreadRenderer": {
                          "post": {
                            "backstagePostRenderer": {
                              "postId": "post-1",
                              "authorText": {"runs": [{"text": "Flow"}]},
                              "authorThumbnail": {"thumbnails": [
                                {"url": "//avatar-small", "width": 40, "height": 40},
                                {"url": "//avatar-large", "width": 80, "height": 80}
                              ]},
                              "contentText": {"runs": [{"text": "Hello "}, {"text": "community"}]},
                              "publishedTimeText": {"simpleText": "2 hours ago"},
                              "voteCount": {"simpleText": "1.2K"},
                              "backstageAttachment": {
                                "backstageImageRenderer": {
                                  "image": {"thumbnails": [
                                    {"url": "//post-small", "width": 320, "height": 180},
                                    {"url": "//post-large", "width": 1280, "height": 720}
                                  ]}
                                }
                              },
                              "actionButtons": {
                                "commentActionButtonsRenderer": {
                                  "replyButton": {
                                    "buttonRenderer": {
                                      "accessibilityData": {"accessibilityData": {"label": "Comment (45)"}},
                                      "navigationEndpoint": {
                                        "signInEndpoint": {
                                          "nextEndpoint": {"browseEndpoint": {"params": "post-params"}}
                                        }
                                      }
                                    }
                                  }
                                }
                              }
                            }
                          }
                        }
                      }
                    }
                  }, {
                    "continuationItemRenderer": {
                      "continuationEndpoint": {"continuationCommand": {"token": "posts-next"}}
                    }
                  }]
                }
                """.trimIndent(),
            )

        val page = response.toCommunityPostsPage("Fallback", "fallback-avatar")
        val post = page.posts.single()
        assertEquals("post-1", post.id)
        assertEquals("Flow", post.authorName)
        assertEquals("https://avatar-large", post.authorAvatarUrl)
        assertEquals("Hello community", post.text)
        assertEquals(listOf("https://post-large"), (post.attachment as PostAttachment.Images).urls)
        assertEquals("1.2K", post.likeCountText)
        assertEquals("45", post.commentCountText)
        assertEquals("post-params", post.commentEndpointParams)
        assertEquals("posts-next", page.continuation)
    }

    private fun post(
        id: String,
        withAuthorEndpoint: Boolean = true,
    ): String {
        val endpoint = if (withAuthorEndpoint) """"authorEndpoint": {"browseEndpoint": {"browseId": "UCauthor"}},""" else ""
        return """
            {"backstagePostThreadRenderer": {"post": {"backstagePostRenderer": {
              "postId": "$id",
              "authorText": {"runs": [{"text": "Bench Notes", "navigationEndpoint": {"browseEndpoint": {"browseId": "UCfromtext"}}}]},
              $endpoint
              "contentText": {"runs": [{"text": "Hello"}]}
            }}}}
            """
    }

    private fun aboutPanelHeader(token: String) =
        """
        "header": {"pageHeaderRenderer": {"content": {"pageHeaderViewModel": {"description": {"descriptionPreviewViewModel": {
          "rendererContext": {"commandContext": {"onTap": {"innertubeCommand": {"showEngagementPanelEndpoint": {"engagementPanel": {
            "engagementPanelSectionListRenderer": {"content": {"sectionListRenderer": {"contents": [{"itemSectionRenderer": {"contents": [
              {"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": "$token"}}}}
            ]}}]}}}
          }}}}}}
        }}}}}}
        """

    private fun postsTab(vararg items: String) =
        """
        "contents": {"twoColumnBrowseResultsRenderer": {"tabs": [{"tabRenderer": {"selected": true, "content": {
          "sectionListRenderer": {"contents": [{"itemSectionRenderer": {"contents": [${items.joinToString(",")}]}}]}
        }}}]}}
        """

    @Test
    fun `keeps each post's author channel`() {
        val page =
            Json
                .parseToJsonElement("{${postsTab(post("p1"), post("p2", withAuthorEndpoint = false))}}")
                .toCommunityPostsPage("Fallback", "")

        assertEquals(listOf("UCauthor", "UCfromtext"), page.posts.map { it.authorChannelId })
    }

    @Test
    fun `the last page of posts has no next page, though the header carries an about token`() {
        val page =
            Json
                .parseToJsonElement("{${aboutPanelHeader("about-token")}, ${postsTab(post("p1"))}}")
                .toCommunityPostsPage("Fallback", "")

        assertEquals(1, page.posts.size)
        assertNull(page.continuation)
    }

    @Test
    fun `the next page comes from the posts list even when the header comes first`() {
        val next = """{"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": "posts-next"}}}}"""
        val page =
            Json
                .parseToJsonElement("{${aboutPanelHeader("about-token")}, ${postsTab(post("p1"), next)}}")
                .toCommunityPostsPage("Fallback", "")

        assertEquals("posts-next", page.continuation)
    }

    @Test
    fun `a posts page append reads its own next token`() {
        val page =
            Json
                .parseToJsonElement(
                    """
                    {"onResponseReceivedEndpoints": [{"appendContinuationItemsAction": {"continuationItems": [
                      ${post("p9")},
                      {"continuationItemRenderer": {"continuationEndpoint": {"continuationCommand": {"token": "page-3"}}}}
                    ]}}]}
                    """,
                ).toCommunityPostsPage("Fallback", "")

        assertEquals(listOf("p9"), page.posts.map { it.id })
        assertEquals("page-3", page.continuation)
    }

    @Test
    fun `keeps the links, timestamps, hashtags and mentions in a post's text`() {
        val response =
            Json.parseToJsonElement(
                """
                {"contents": [{"backstagePostThreadRenderer": {"post": {"backstagePostRenderer": {
                  "postId": "rich",
                  "contentText": {"runs": [
                    {"text": "Full video at "},
                    {"text": "4:12", "navigationEndpoint": {"watchEndpoint": {"videoId": "dQw4w9WgXcQ", "startTimeSeconds": 252}}},
                    {"text": " in "},
                    {"text": "youtu.be/…", "navigationEndpoint": {"urlEndpoint": {
                      "url": "https://www.youtube.com/redirect?q=https%3A%2F%2Fexample.com%2Fpage&v=x"}}},
                    {"text": " "},
                    {"text": "#mkbhd", "navigationEndpoint": {"browseEndpoint": {"browseId": "FEhashtag", "canonicalBaseUrl": "/hashtag/mkbhd"}}},
                    {"text": " with "},
                    {"text": "@friend", "navigationEndpoint": {"browseEndpoint": {"browseId": "UCWqW23Ko6dbscptZYyQE-8A"}}}
                  ]}
                }}}}]}
                """,
            )

        val post = response.toCommunityPostsPage("Fallback", "").posts.single()
        val rich = requireNotNull(post.richText)

        assertEquals(post.text, rich.text)
        assertEquals(
            listOf(
                RichTextTarget.Video("dQw4w9WgXcQ", 252),
                RichTextTarget.Url("https://example.com/page"),
                RichTextTarget.Hashtag("mkbhd"),
                RichTextTarget.Channel("UCWqW23Ko6dbscptZYyQE-8A"),
            ),
            rich.spans.map { it.target },
        )
        assertEquals("4:12", rich.text.substring(rich.spans[0].start, rich.spans[0].end))
    }

    @Test
    fun `a plain post has no marked spans`() {
        val post =
            Json
                .parseToJsonElement("{${postsTab(post("plain"))}}")
                .toCommunityPostsPage("Fallback", "")
                .posts
                .single()

        assertEquals("Hello", post.richText?.text)
        assertEquals(emptyList<Any>(), post.richText?.spans)
    }

    @Test
    fun `parses modern entity comments with reply and page continuations`() {
        val response =
            Json.parseToJsonElement(
                """
                {
                  "onResponseReceivedEndpoints": [{
                    "appendContinuationItemsAction": {
                      "continuationItems": [{
                        "commentThreadRenderer": {
                          "commentViewModel": {
                            "commentKey": "comment-key",
                            "commentId": "comment-fallback"
                          },
                          "replies": {
                            "commentRepliesRenderer": {
                              "contents": [{
                                "continuationItemRenderer": {
                                  "continuationEndpoint": {"continuationCommand": {"token": "replies-next"}}
                                }
                              }]
                            }
                          }
                        }
                      }, {
                        "continuationItemRenderer": {
                          "continuationEndpoint": {"continuationCommand": {"token": "comments-next"}}
                        }
                      }]
                    }
                  }],
                  "frameworkUpdates": {
                    "entityBatchUpdate": {
                      "mutations": [{
                        "entityKey": "comment-key",
                        "payload": {
                          "commentEntityPayload": {
                            "properties": {
                              "commentId": "comment-1",
                              "content": "Modern comment",
                              "publishedTime": "5 hours ago"
                            },
                            "author": {
                              "displayName": "Viewer",
                              "channelId": "UCviewer",
                              "avatar": {"image": {"sources": [{"url": "//viewer-avatar"}]}}
                            },
                            "toolbar": {
                              "replyCount": "3 replies",
                              "likeCountNotliked": "42"
                            }
                          }
                        }
                      }]
                    }
                  }
                }
                """.trimIndent(),
            )

        val page = response.toCommunityCommentsPage()
        val comment = page.comments.single()
        assertEquals("comment-1", comment.id)
        assertEquals("Viewer", comment.author)
        assertEquals("https://viewer-avatar", comment.authorThumbnail)
        assertEquals("Modern comment", comment.text)
        assertEquals(42, comment.likeCount)
        assertEquals(3, comment.replyCount)
        assertEquals("UCviewer", comment.authorChannelId)
        assertEquals("replies-next", comment.continuationToken)
        assertEquals("comments-next", page.continuation)
    }

    /** #904: post comments name the avatar with a flat author URL and carry no `avatar` object. */
    @Test
    fun `reads the avatar from the flat author url`() {
        val response =
            Json.parseToJsonElement(
                """
                {
                  "commentThreadRenderer": {
                    "commentViewModel": {"commentKey": "comment-key"}
                  },
                  "frameworkUpdates": {
                    "entityBatchUpdate": {
                      "mutations": [{
                        "entityKey": "comment-key",
                        "payload": {
                          "commentEntityPayload": {
                            "properties": {"commentId": "comment-1", "content": "Nice post"},
                            "author": {
                              "displayName": "@viewer",
                              "channelId": "UCviewer",
                              "avatarThumbnailUrl": "https://yt3.ggpht.com/viewer=s88-c-k-c0x00ffffff-no-rj"
                            }
                          }
                        }
                      }]
                    }
                  }
                }
                """.trimIndent(),
            )

        val comment = response.toCommunityCommentsPage().comments.single()
        assertEquals("https://yt3.ggpht.com/viewer=s88-c-k-c0x00ffffff-no-rj", comment.authorThumbnail)
    }

    @Test
    fun `reads the post's own sort menu, names and tokens as the server sent them`() {
        val response =
            Json.parseToJsonElement(
                """
                {
                  "onResponseReceivedEndpoints": [{
                    "reloadContinuationItemsCommand": {
                      "continuationItems": [{
                        "commentsHeaderRenderer": {
                          "sortMenu": {
                            "sortFilterSubMenuRenderer": {
                              "subMenuItems": [
                                {"title": "Destacados", "selected": true,
                                 "serviceEndpoint": {"continuationCommand": {"token": "top-token"}}},
                                {"title": "Más recientes", "selected": false,
                                 "serviceEndpoint": {"continuationCommand": {"token": "newest-token"}}}
                              ]
                            }
                          }
                        }
                      }]
                    }
                  }]
                }
                """.trimIndent(),
            )

        val page = response.toCommunityCommentsPage()
        assertEquals(listOf("Destacados", "Más recientes"), page.sortOptions.map { it.title })
        assertEquals(listOf("top-token", "newest-token"), page.sortOptions.map { it.token })
        assertEquals(null, page.continuation)
    }

    @Test
    fun `parses legacy comment renderer`() {
        val response =
            Json.parseToJsonElement(
                """
                {
                  "commentThreadRenderer": {
                    "comment": {
                      "commentRenderer": {
                        "commentId": "legacy-1",
                        "authorText": {"simpleText": "Legacy viewer"},
                        "authorThumbnail": {"thumbnails": [{"url": "https://legacy-avatar"}]},
                        "contentText": {"simpleText": "Legacy comment"},
                        "publishedTimeText": {"simpleText": "1 day ago"},
                        "voteCount": {"simpleText": "1.5K"},
                        "replyCount": 0,
                        "authorEndpoint": {"browseEndpoint": {"browseId": "UClegacy"}}
                      }
                    }
                  }
                }
                """.trimIndent(),
            )

        val page = response.toCommunityCommentsPage()
        val comment = page.comments.single()
        assertEquals("legacy-1", comment.id)
        assertEquals(1500, comment.likeCount)
        assertEquals("UClegacy", comment.authorChannelId)
        assertNull(page.continuation)
    }
}
