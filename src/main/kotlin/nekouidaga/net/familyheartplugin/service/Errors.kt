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

enum class RequestError {
    DUPLICATE_PENDING
}

class RequestException(val error: RequestError) : Exception(error.name)
