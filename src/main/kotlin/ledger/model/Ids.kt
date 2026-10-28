package ledger.model

@JvmInline
value class AccountId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class EventId(val value: String) {
    override fun toString(): String = value
}

@JvmInline
value class AuthId(val value: String) {
    override fun toString(): String = value
}
