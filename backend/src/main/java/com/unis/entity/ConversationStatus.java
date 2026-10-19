package com.unis.entity;

/**
 * Lane state of a 1:1 conversation.
 *
 * PENDING   First contact from someone the recipient has no link with. It sits in
 *           the recipient's Requests: the initiator may send exactly one
 *           text-only message, the recipient is not notified, and no Seen mark
 *           is ever sent back.
 * ACCEPTED  A normal conversation — accepted, replied to, or the pair already
 *           had a link (follow / support) so it never passed through Requests.
 */
public enum ConversationStatus {
    PENDING,
    ACCEPTED
}
