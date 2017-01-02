/**
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.zclient.messages

import android.content.Context
import android.util.AttributeSet
import android.view.{HapticFeedbackConstants, ViewGroup}
import com.waz.ZLog.ImplicitTag._
import com.waz.api.Message
import com.waz.model.ConversationData.ConversationType
import com.waz.model.{Dim2, MessageData, MessageId}
import com.waz.service.messages.MessageAndLikes
import com.waz.utils.RichOption
import com.waz.zclient.controllers.global.SelectionController
import com.waz.zclient.messages.MessageViewLayout.PartDesc
import com.waz.zclient.messages.MsgPart._
import com.waz.zclient.messages.controllers.MessageActionsController
import com.waz.zclient.messages.parts.footer.FooterPartView
import com.waz.zclient.utils.ContextUtils._
import com.waz.zclient.utils.DateConvertUtils.asZonedDateTime
import com.waz.zclient.utils._
import com.waz.zclient.{BuildConfig, R, ViewHelper}
import org.threeten.bp.Instant

class MessageView(context: Context, attrs: AttributeSet, style: Int)
    extends MessageViewLayout(context, attrs, style) with ViewHelper {

  import MessageView._

  def this(context: Context, attrs: AttributeSet) = this(context, attrs, 0)
  def this(context: Context) = this(context, null, 0)

  protected val factory = inject[MessageViewFactory]
  private val selection = inject[SelectionController].messages
  private lazy val messageActions = inject[MessageActionsController]

  private var msgId: MessageId = _
  private var msg: MessageData = MessageData.Empty
  private var data: MessageAndLikes = MessageAndLikes.Empty

  private var hasFooter = false

  setClipChildren(false)
  setClipToPadding(false)

  this.onClick {
    selection.toggleFocused(msgId)
  }

  this.onLongClick {
    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    messageActions.showDialog(data)
  }

  def set(mAndL: MessageAndLikes, prev: Option[MessageData], opts: MsgBindOptions): Unit = {
    val animateFooter = msgId == mAndL.message.id && hasFooter != shouldShowFooter(mAndL, opts)
    hasFooter = shouldShowFooter(mAndL, opts)
    data = mAndL
    msg = mAndL.message
    msgId = msg.id

    val contentParts = {
      if (msg.msgType != Message.Type.RICH_MEDIA) Seq(PartDesc(MsgPart(msg.msgType)))
      else msg.content map { content => PartDesc(MsgPart(content.tpe), Some(content)) }
    } .filter(_.tpe != MsgPart.Empty)

    val parts =
      if (!BuildConfig.DEBUG && contentParts.forall(_.tpe == MsgPart.Unknown)) Nil // don't display anything for unknown message
      else {
        val builder = Seq.newBuilder[PartDesc]

        getSeparatorType(msg, prev, opts.isFirstUnread).foreach(sep => builder += PartDesc(sep))

        if (shouldShowChathead(msg, prev))
          builder += PartDesc(MsgPart.User)

        if (shouldShowInviteBanner(msg, opts)) {
          builder += PartDesc(MsgPart.InviteBanner)
        }
        builder ++= contentParts

        if (msg.isEphemeral) {
          builder += PartDesc(MsgPart.EphemeralDots)
        }

        if (hasFooter || animateFooter)
          builder += PartDesc(MsgPart.Footer)

        builder.result()
      }

    // don't use margin on message view for spacing, this produces gaps ignoring click events, also margin change forces layout requests
    // TODO: this should be split between this and previous view
    // TODO: last view should have bottom padding to avoid overlaps with cursor
    val pad = if (parts.isEmpty) 0 else getTopMargin(prev.map(_.msgType), parts.head.tpe)
    setPadding(0, pad, 0, 0)
    setParts(mAndL, parts, opts)

    if (animateFooter)
      getFooter foreach { footer =>
        if (hasFooter) footer.slideContentIn()
        else footer.slideContentOut()
      }
  }

  def isFooterHiding = !hasFooter && getFooter.isDefined

  private def getSeparatorType(msg: MessageData, prev: Option[MessageData], isFirstUnread: Boolean): Option[MsgPart] = msg.msgType match {
    case Message.Type.CONNECT_REQUEST => None
    case _ =>
      prev.fold2(None, { p =>
        val prevDay = asZonedDateTime(p.time).toLocalDate.atStartOfDay()
        val curDay = asZonedDateTime(msg.time).toLocalDate.atStartOfDay()

        if (prevDay.isBefore(curDay)) Some(SeparatorLarge)
        else if (p.time.isBefore(msg.time.minusSeconds(1800)) || isFirstUnread) Some(Separator)
        else None
      })
  }

  private def shouldShowChathead(msg: MessageData, prev: Option[MessageData]) = {
    val userChanged = prev.exists(m => m.userId != msg.userId || m.isSystemMessage)
    val recalled = msg.msgType == Message.Type.RECALLED
    val edited = msg.editTime != Instant.EPOCH
    val knock = msg.msgType == Message.Type.KNOCK

    !knock && !msg.isSystemMessage && (recalled || edited || userChanged)
  }

  private def shouldShowInviteBanner(msg: MessageData, opts: MsgBindOptions) =
    opts.position == 0 && msg.msgType == Message.Type.MEMBER_JOIN && opts.convType == ConversationType.Group

  private def shouldShowFooter(mAndL: MessageAndLikes, opts: MsgBindOptions): Boolean =
    mAndL.likes.nonEmpty || selection.isFocused(mAndL.message.id) || opts.isLastSelf

  def getFooter = listParts.lastOption.collect { case footer: FooterPartView => footer }
}

object MessageView {

  val focusableTypes = Set(
    Message.Type.TEXT,
    Message.Type.TEXT_EMOJI_ONLY,
    Message.Type.ANY_ASSET,
    Message.Type.ASSET,
    Message.Type.AUDIO_ASSET,
    Message.Type.VIDEO_ASSET,
    Message.Type.LOCATION,
    Message.Type.RICH_MEDIA
  )

  val GenericMessage = 0

  def viewType(tpe: Message.Type): Int = tpe match {
    case _ => GenericMessage
  }

  def apply(parent: ViewGroup, tpe: Int): MessageView = tpe match {
    case _ => ViewHelper.inflate[MessageView](R.layout.message_view, parent, addToParent = false)
  }

  trait MarginRule

  case object TextLike extends MarginRule
  case object ImageLike extends MarginRule
  case object FileLike extends MarginRule
  case object SystemLike extends MarginRule
  case object Ping extends MarginRule
  case object MissedCall extends MarginRule
  case object Other extends MarginRule

  object MarginRule {
    def apply(tpe: Message.Type): MarginRule = apply(MsgPart(tpe))

    def apply(tpe: MsgPart): MarginRule = {
      tpe match {
        case Separator |
             SeparatorLarge |
             User |
             Text => TextLike
        case MsgPart.Ping => Ping
        case FileAsset |
             AudioAsset |
             WebLink |
             YouTube |
             Location |
             SoundCloud => FileLike
        case Image | VideoAsset => ImageLike
        case MsgPart.MemberChange |
             MsgPart.OtrMessage |
             MsgPart.Rename => SystemLike
        case MsgPart.MissedCall => MissedCall
        case _ => Other
      }
    }
  }

  def getTopMargin(prevTpe: Option[Message.Type], topPart: MsgPart)(implicit context: Context): Int = {
    if (prevTpe.isEmpty)
      if (MarginRule(topPart) == SystemLike) toPx(24) else 0
    else {
      val p = (MarginRule(prevTpe.get), MarginRule(topPart)) match {
        case (TextLike, TextLike)         => 8
        case (TextLike, FileLike)         => 16
        case (FileLike, FileLike)         => 10
        case (ImageLike, ImageLike)       => 4
        case (FileLike | ImageLike, _) |
             (_, FileLike | ImageLike)    => 10
        case (MissedCall, _)              => 24
        case (SystemLike, _) |
             (_, SystemLike)              => 24
        case (_, Ping) | (Ping, _)        => 14
        case (_, MissedCall)              => 24
        case _                            => 0
      }
      toPx(p)
    }
  }

  // Message properties calculated while binding, may not be directly related to message state,
  // should not be cached in message view as those can be valid only while set method is called
  case class MsgBindOptions(
                             position: Int,
                             isSelf: Boolean,
                             isLast: Boolean,
                             isLastSelf: Boolean, // last self message in conv
                             isFirstUnread: Boolean,
                             listDimensions: Dim2,
                             convType: ConversationType
                       )
}



