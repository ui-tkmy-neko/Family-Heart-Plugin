package nekouidaga.net.familyheartplugin.model
import java.time.Instant
import java.util.UUID
enum class RelationshipType { SPOUSE, PARENT_CHILD }
enum class RelationshipStatus { ACTIVE, REMOVED }
enum class SpouseRole { WIFE, HUSBAND }
enum class ParentChildRole { PARENT, CHILD }
enum class RequestType { MARRY, DIVORCE, CHILD_PARENT, SEPARATION, SKINSHIP }
enum class RequestStatus { PENDING, PROCESSING, ACCEPTED, DENIED, CANCELLED, EXPIRED }
enum class RequestProcessingGuard { ECONOMY_INTENT, ECONOMY_CHARGED, SKINSHIP_INTENT, SKINSHIP_EXECUTED }
enum class ActionExecutionState { INTENT, EXECUTED }
enum class PenaltyTargetType { PLAYER, FAMILY, RELATIONSHIP }
data class PlayerRecord(val uuid:UUID,val mcid:String,val firstSeen:Instant,val lastSeen:Instant)
data class FamilyRelationship(val internalId:Long,val relationshipId:String,val playerA:UUID,val playerB:UUID,val type:RelationshipType,val roleA:String,val roleB:String,val autoAdded:Boolean, val autoSourceRelationshipId:String?, var status:RelationshipStatus,val createdAt:Instant,var updatedAt:Instant){fun involves(u:UUID)=playerA==u||playerB==u;fun getOther(u:UUID)=when(u){playerA->playerB;playerB->playerA;else->error("not participant")};fun roleOf(u:UUID)=when(u){playerA->roleA;playerB->roleB;else->error("not participant")}}
data class RelationshipRequest(val id:Long,val requester:UUID,val target:UUID,val type:RequestType,val metadata:String?,var status:RequestStatus,val createdAt:Instant,var updatedAt:Instant,val processingGuard:RequestProcessingGuard?)
data class FamilyPenalty(val id:Long,val targetType:PenaltyTargetType,val targetPlayer:UUID?,val targetRelationship:String?,val effect:String,val value:Double,val multiplier:Double,val startedAt:Instant,val endsAt:Instant?,val removable:Boolean,var active:Boolean)
data class BuffDefinition(val key:String,val enabled:Boolean,val condition:String,val required:Double,val amplifier:Int,val duration:Int,val effect:String,val multiplier:Double)
