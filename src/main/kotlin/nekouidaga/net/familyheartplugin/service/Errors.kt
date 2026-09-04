package nekouidaga.net.familyheartplugin.service

enum class RelationshipError {
    SELF_TARGET,
    ALREADY_HAS_SPOUSE,
    TARGET_ALREADY_HAS_SPOUSE,
    ALREADY_MAX_PARENTS,
    DUPLICATE_RELATIONSHIP,
    RELATIONSHIP_NOT_FOUND,
    NOT_A_PARTICIPANT
}

class RelationshipException(val error: RelationshipError) : Exception(error.name)

/**
 * バグ修正(監査): RelationshipException(ALREADY_HAS_SPOUSE等)はrequest.decide()の
 * completeDecision()内(createWithinTransaction/removeWithinTransaction)からのみ投げられるが、
 * 呼び出し元(FamilyHeartCommandのaccept、GuiManagerのRequests/MARRIAGE_ROLEクリック)は
 * 例外を cause.message の文字列switchでしか判定しておらず、RelationshipExceptionは
 * どのcaseにも一致せず常に汎用的な general.database-error に丸められていた。
 * messages.yml には relationship.already-spouse 等の専用メッセージが定義済みだが、
 * この対応表が無いために到達不能だった。
 */
fun RelationshipError.messageKey(): String = when (this) {
    RelationshipError.SELF_TARGET -> "relationship.self"
    RelationshipError.ALREADY_HAS_SPOUSE -> "relationship.already-spouse"
    RelationshipError.TARGET_ALREADY_HAS_SPOUSE -> "relationship.target-already-spouse"
    RelationshipError.ALREADY_MAX_PARENTS -> "relationship.max-parents"
    RelationshipError.DUPLICATE_RELATIONSHIP -> "relationship.duplicate"
    RelationshipError.RELATIONSHIP_NOT_FOUND -> "relationship.not-found"
    RelationshipError.NOT_A_PARTICIPANT -> "relationship.not-found"
}

enum class RequestError {
    DUPLICATE_PENDING
}

class RequestException(val error: RequestError) : Exception(error.name)
