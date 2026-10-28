package ledger.model

/**
 * Approval to post an event value-dated before the checkpoint. With a valid one the event is
 * accepted but re-dated to the day after the checkpoint. See AMBIGUITIES.md, Entries Dated Before the Checkpoint.
 */
data class BackdatedAuthorization(val reference: String, val approvedBy: String) {
    /**
     * Dummy: any non-blank reference and approver is valid. In production this would check that
     * the approver has the right to approve it and that the approval covers this event.
     */
    val isValid: Boolean get() = reference.isNotBlank() && approvedBy.isNotBlank()
}
