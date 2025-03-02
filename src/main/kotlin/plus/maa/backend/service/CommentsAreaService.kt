package plus.maa.backend.service

import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import plus.maa.backend.common.utils.requireNotNull
import plus.maa.backend.controller.request.comments.CommentsAddDTO
import plus.maa.backend.controller.request.comments.CommentsQueriesDTO
import plus.maa.backend.controller.request.comments.CommentsRatingDTO
import plus.maa.backend.controller.request.comments.CommentsToppingDTO
import plus.maa.backend.controller.response.comments.CommentsAreaInfo
import plus.maa.backend.controller.response.comments.CommentsInfo
import plus.maa.backend.controller.response.comments.SubCommentsInfo
import plus.maa.backend.repository.CommentsAreaRepository
import plus.maa.backend.repository.CopilotRepository
import plus.maa.backend.repository.RatingRepository
import plus.maa.backend.repository.UserRepository
import plus.maa.backend.repository.entity.CommentsArea
import plus.maa.backend.repository.entity.Copilot
import plus.maa.backend.repository.entity.MaaUser
import plus.maa.backend.repository.entity.Rating
import plus.maa.backend.service.model.RatingType
import java.time.LocalDateTime

/**
 * @author LoMu
 * Date  2023-02-17 15:00
 */
@Service
class CommentsAreaService(
    private val commentsAreaRepository: CommentsAreaRepository,
    private val ratingRepository: RatingRepository,
    private val copilotRepository: CopilotRepository,
    private val userRepository: UserRepository,
    private val emailService: EmailService,
) {
    /**
     * 评论
     * 每个评论都有一个uuid加持
     *
     * @param userId         登录用户 id
     * @param commentsAddDTO CommentsRequest
     */
    fun addComments(userId: String, commentsAddDTO: CommentsAddDTO) {
        val copilotId = commentsAddDTO.copilotId
        val copilot = copilotRepository.findByCopilotId(copilotId).requireNotNull { "作业不存在" }

        val parentCommentId = commentsAddDTO.fromCommentId?.ifBlank { null }
        // 指定了回复对象但该对象不存在时抛出异常
        val parentComment = parentCommentId?.let { id -> requireCommentsAreaById(id) { "回复的评论不存在" } }

        notifyRelatedUser(userId, commentsAddDTO.message, copilot, parentComment)

        val comment = CommentsArea(
            copilotId = copilotId,
            uploaderId = userId,
            fromCommentId = parentComment?.id,
            mainCommentId = parentComment?.run { mainCommentId ?: id },
            message = commentsAddDTO.message,
            notification = commentsAddDTO.notification,
        )
        commentsAreaRepository.insert(comment)
    }

    private fun notifyRelatedUser(replierId: String, message: String, copilot: Copilot, parentComment: CommentsArea?) {
        if (parentComment?.notification == false) return
        val receiverId = parentComment?.uploaderId ?: copilot.uploaderId
        if (receiverId == null || receiverId == replierId) return

        val userMap = userRepository.findAllById(listOf(receiverId, replierId)).associateBy(MaaUser::userId)
        val receiver = userMap[receiverId] ?: return
        val replier = userMap.getOrDefault(replierId, MaaUser.UNKNOWN)

        val targetMsg = parentComment?.message ?: copilot.doc?.title ?: ""
        emailService.sendCommentNotification(receiver.email, receiver.userName, targetMsg, replier.userName, message)
    }

    fun deleteComments(userId: String, commentsId: String) {
        val commentsArea = requireCommentsAreaById(commentsId)
        // 允许作者删除评论
        val copilot = copilotRepository.findByCopilotId(commentsArea.copilotId)
        require(userId == copilot?.uploaderId || userId == commentsArea.uploaderId) { "您无法删除不属于您的评论" }

        val now = LocalDateTime.now()
        commentsArea.delete = true
        commentsArea.deleteTime = now
        val comments = mutableListOf(commentsArea)
        // 删除所有回复
        if (commentsArea.mainCommentId.isNullOrBlank()) {
            comments += commentsAreaRepository.findByMainCommentId(commentsId).onEach { ca ->
                ca.deleteTime = now
                ca.delete = true
            }
        }
        commentsAreaRepository.saveAll(comments)
    }

    /**
     * 为评论进行点赞
     *
     * @param userId            登录用户 id
     * @param commentsRatingDTO CommentsRatingDTO
     */
    fun rates(userId: String, commentsRatingDTO: CommentsRatingDTO) {
        val commentId = commentsRatingDTO.commentId
        val commentsArea = requireCommentsAreaById(commentId)

        val rating = ratingRepository.findByTypeAndKeyAndUserId(
            Rating.KeyType.COMMENT,
            commentId,
            userId,
        ) ?: Rating(
            null,
            Rating.KeyType.COMMENT,
            commentId,
            userId,
            RatingType.NONE,
            LocalDateTime.now(),
        )

        val prevType = rating.rating
        val nextType = RatingType.fromRatingType(commentsRatingDTO.rating)
        // 如果评分未发生变化则返回
        if (nextType == prevType) return

        rating.rating = nextType
        rating.rateTime = LocalDateTime.now()
        ratingRepository.save(rating)

        // 更新评分后更新评论的点赞数
        val likeCountChange = nextType.countLike() - prevType.countLike()
        val dislikeCountChange = nextType.countDislike() - prevType.countDislike()

        // 点赞数不需要在高并发下特别精准，大概就行，但是也得避免特别离谱的数字
        commentsArea.likeCount = (commentsArea.likeCount + likeCountChange).coerceAtLeast(0)
        commentsArea.dislikeCount = (commentsArea.dislikeCount + dislikeCountChange).coerceAtLeast(0)

        commentsAreaRepository.save(commentsArea)
    }

    /**
     * 评论置顶
     *
     * @param userId             登录用户 id
     * @param commentsToppingDTO CommentsToppingDTO
     */
    fun topping(userId: String, commentsToppingDTO: CommentsToppingDTO) {
        val commentsArea = requireCommentsAreaById(commentsToppingDTO.commentId)
        // 只允许作者置顶评论
        val copilot = copilotRepository.findByCopilotId(commentsArea.copilotId)
        require(userId == copilot?.uploaderId) { "只有作者才能置顶评论" }

        commentsArea.topping = commentsToppingDTO.topping
        commentsAreaRepository.save(commentsArea)
    }

    /**
     * 查询
     *
     * @param request CommentsQueriesDTO
     * @return CommentsAreaInfo
     */
    fun queriesCommentsArea(request: CommentsQueriesDTO): CommentsAreaInfo {
        val toppingOrder = Sort.Order.desc("topping")
        val sortOrder = Sort.Order(
            if (request.desc) Sort.Direction.DESC else Sort.Direction.ASC,
            when (request.orderBy) {
                "hot" -> "likeCount"
                "id" -> "uploadTime"
                else -> request.orderBy ?: "likeCount"
            },
        )
        val page = (request.page - 1).coerceAtLeast(0)
        val limit = if (request.limit > 0) request.limit else 10
        val pageable: Pageable = PageRequest.of(page, limit, Sort.by(toppingOrder, sortOrder))

        // 主评论
        val mainCommentsPage = if (!request.justSeeId.isNullOrBlank()) {
            commentsAreaRepository.findByCopilotIdAndUploaderIdAndDeleteAndMainCommentIdExists(
                request.copilotId,
                request.justSeeId,
                delete = false,
                exists = false,
                pageable = pageable,
            )
        } else {
            commentsAreaRepository.findByCopilotIdAndDeleteAndMainCommentIdExists(
                request.copilotId,
                delete = false,
                exists = false,
                pageable = pageable,
            )
        }

        val mainCommentIds = mainCommentsPage.map(CommentsArea::id).filterNotNull()
        // 获取子评论
        val subCommentsList = commentsAreaRepository.findByMainCommentIdIn(mainCommentIds).onEach {
            // 将已删除评论内容替换为空
            if (it.delete) it.message = ""
        }

        // 获取所有评论用户
        val allUserIds = (mainCommentsPage + subCommentsList).map(CommentsArea::uploaderId).distinct()
        val users = userRepository.findAllById(allUserIds).associateBy(MaaUser::userId)
        val subCommentGroups = subCommentsList.groupBy(CommentsArea::mainCommentId)

        // 转换主评论数据并填充用户名
        val commentsInfos = mainCommentsPage.toList().map { mainComment ->
            val subCommentsInfos = (subCommentGroups[mainComment.id] ?: emptyList()).map { c ->
                buildSubCommentsInfo(c, users[c.uploaderId] ?: MaaUser.UNKNOWN)
            }
            buildMainCommentsInfo(mainComment, users[mainComment.uploaderId] ?: MaaUser.UNKNOWN, subCommentsInfos)
        }

        return CommentsAreaInfo(
            hasNext = mainCommentsPage.hasNext(),
            page = mainCommentsPage.totalPages,
            total = mainCommentsPage.totalElements,
            data = commentsInfos,
        )
    }

    /**
     * 转换子评论数据并填充用户名
     */
    private fun buildSubCommentsInfo(c: CommentsArea, user: MaaUser) = SubCommentsInfo(
        commentId = c.id!!,
        uploader = user.userName,
        uploaderId = c.uploaderId,
        message = c.message,
        uploadTime = c.uploadTime,
        like = c.likeCount,
        dislike = c.dislikeCount,
        fromCommentId = c.fromCommentId!!,
        mainCommentId = c.mainCommentId!!,
        deleted = c.delete,
    )

    private fun buildMainCommentsInfo(c: CommentsArea, user: MaaUser, subList: List<SubCommentsInfo>) = CommentsInfo(
        commentId = c.id!!,
        uploader = user.userName,
        uploaderId = c.uploaderId,
        message = c.message,
        uploadTime = c.uploadTime,
        like = c.likeCount,
        dislike = c.dislikeCount,
        topping = c.topping,
        subCommentsInfos = subList,
    )

    fun notificationStatus(userId: String, id: String, status: Boolean) {
        val commentsArea = requireCommentsAreaById(id)
        require(userId == commentsArea.uploaderId) { "您没有权限修改" }
        commentsArea.notification = status
        commentsAreaRepository.save(commentsArea)
    }

    private fun requireCommentsAreaById(commentsId: String, lazyMessage: () -> Any = { "评论不存在" }): CommentsArea =
        commentsAreaRepository.findByIdOrNull(commentsId)?.takeIf { !it.delete }.requireNotNull(lazyMessage)
}
