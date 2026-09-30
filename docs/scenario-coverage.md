# Scenario coverage

Every scenario in `openspec/specs/` has at least one test named after it
(the scenario title in camelCase). Unit tests end in `Test`, integration tests in `IT`.

## audit-history

| Scenario | Test |
|---|---|
| Full lifecycle is auditable | `ReadApiIT.fullLifecycleIsAuditable` |
| Suppression is auditable | `EventDedupIT.suppressionIsAuditable` |
| Audit for unknown notification | `ReadApiIT.auditForUnknownNotification` |
| Audit owned by another source system | `ReadApiIT.auditOwnedByAnotherSourceSystem` |
| Message content excluded | `LoggingIT.messageContentExcluded`, `SensitiveDataIT.messageContentExcluded` |
| Address masked | `AddressMaskerTest.addressMasked`, `SensitiveDataIT.addressMasked` |
| Webhook URL masked | `WebhookSensitiveDataIT.webhookUrlMasked` |
| Signing secret never logged | `WebhookSensitiveDataIT.signingSecretNeverLogged` |

## channel-routing

| Scenario | Test |
|---|---|
| Requested channel honored | `RoutingPolicyTest.requestedChannelHonored` |
| Critical severity escalates | `RoutingPolicyTest.criticalSeverityEscalates` |
| Opt-out respected with fallback | `RoutingPolicyTest.optOutRespectedWithFallback` |
| No deliverable channel for one recipient | `RoutingPolicyTest.noDeliverableChannelForOneRecipient` |
| No deliverable recipient at all | `RoutingPolicyTest.noDeliverableRecipientAtAll`, `SubmissionIT.noDeliverableRecipientAtAll` |
| Webhook routed when requested | `RoutingPolicyTest.webhookRoutedWhenRequested` |
| Webhook never added by defaults | `RoutingPolicyTest.webhookNeverAddedByDefaults` |
| Disabled webhook falls back | `RoutingPolicyTest.disabledWebhookFallsBack`, `WebhookDisabledIT.disabledWebhookFallsBack` |
| Decision reasons recorded | `RoutingPolicyTest.decisionReasonsRecorded`, `SubmissionIT.decisionReasonsRecorded` |
| Unknown recipient | `RoutingCharacterizationTest.unknownRecipient`, `RoutingPolicyTest.unknownRecipient` |
| Webhook in routing configuration rejected | `RoutingConfigGuardTest.webhookInRoutingConfigurationRejected` |

## delivery-processing

| Scenario | Test |
|---|---|
| Successful delivery | `DeliveryProcessingIT.successfulDelivery` |
| Priority ordering | `DeliveryProcessingIT.priorityOrdering` |
| Waiting delivery gains priority | `PriorityAgingIT.waitingDeliveryGainsPriority` |
| Aging disabled | `PriorityAgingDisabledIT.agingDisabled` |
| Retry competes with new work | `PriorityAgingIT.retryCompetesWithNewWork` |
| Backoff does not count as waiting | `PriorityAgingIT.backoffDoesNotCountAsWaiting` |
| Reclaimed lease ordered by lease expiry | `PriorityAgingIT.reclaimedLeaseOrderedByLeaseExpiry` |
| Negative aging interval rejected | `PriorityAgingPropertiesTest.negativeAgingIntervalRejected` |
| Unexpected exception | `UnexpectedProviderExceptionIT.unexpectedException` |
| Transient failure then success | `DeliveryProcessingIT.transientFailureThenSuccess` |
| Retries exhausted | `DeliveryProcessingIT.retriesExhausted` |
| Permanent failure is not retried | `DeliveryProcessingIT.permanentFailureIsNotRetried` |
| Rate limit honored | `DeliveryProcessingIT.rateLimitHonored` |
| Expires while waiting to retry | `DeliveryProcessingIT.expiresWhileWaitingToRetry` |
| Claimed after expiry | `DeliveryProcessingIT.claimedAfterExpiry` |
| Injected failure | `SimulatedProviderTest.injectedFailure` |
| Queue wait measured | `PriorityObservabilityIT.queueWaitMeasured` |
| Never-attempted expiry flagged | `PriorityObservabilityIT.neverAttemptedExpiryFlagged` |
| Expiry after an attempt | `PriorityObservabilityIT.expiryAfterAnAttempt` |

## event-deduplication

| Scenario | Test |
|---|---|
| Same event resubmitted with a new key | `EventDedupIT.sameEventResubmittedWithANewKey` |
| Partial overlap | `EventDedupIT.partialOverlap` |
| Different channel is not a duplicate | `EventDedupIT.differentChannelIsNotADuplicate` |
| Outside the window | `EventDedupIT.outsideTheWindow` |
| Original delivery failed | `EventDedupIT.originalDeliveryFailed` |
| Different source systems | `EventDedupIT.differentSourceSystems` |
| Concurrent duplicate events | `EventDedupIT.concurrentDuplicateEvents` |
| Feature disabled | `DuplicateEventDedupOffIT.featureDisabled` |
| Window configured | `EventDedupWindowIT.windowConfigured` |

## idempotency

| Scenario | Test |
|---|---|
| Same key, same payload | `IdempotencyIT.sameKeySamePayload` |
| Same key, different payload | `IdempotencyIT.sameKeyDifferentPayload` |
| Concurrent duplicate submissions | `IdempotencyIT.concurrentDuplicateSubmissions` |
| Same key from different source systems | `IdempotencyIT.sameKeyFromDifferentSourceSystems` |
| Key reusable after retention | `IdempotencyRetentionIT.keyReusableAfterRetention` |
| Worker crashes mid-delivery | `DeliveryProcessingIT.workerCrashesMidDelivery` |
| Two workers race for one delivery | `DeliveryProcessingIT.twoWorkersRaceForOneDelivery` |

## notification-status

| Scenario | Test |
|---|---|
| Status of an in-progress notification | `NotificationStatusDeriverTest.statusOfAnInProgressNotification`, `ReadApiIT.statusOfAnInProgressNotification` |
| Addresses masked | `ReadApiIT.addressesMasked` |
| Suppressed delivery shows its origin | `SuppressedStatusApiIT.suppressedDeliveryShowsItsOrigin` |
| Unknown notification | `ReadApiIT.unknownNotification` |
| Notification owned by another source system | `ReadApiIT.notificationOwnedByAnotherSourceSystem` |
| Derived partial delivery | `NotificationStatusDeriverTest.derivedPartialDelivery` |
| Suppressed deliveries ignored | `NotificationStatusDeriverTest.suppressedDeliveriesIgnored` |
| Fully suppressed notification | `NotificationStatusDeriverTest.fullySuppressedNotification`, `SuppressedStatusApiIT.fullySuppressedNotification` |
| Invalid transition rejected | `DeliveryStateMachineTest.invalidTransitionRejected`, `DeliveryStateMachineTest.invalidTransitionRejectedFromSuppressed` |

## notification-submission

| Scenario | Test |
|---|---|
| Valid notification is accepted | `SubmissionIT.validNotificationIsAccepted` |
| Processing is asynchronous | `SubmissionIT.processingIsAsynchronous` |
| Priority is required | `SubmissionIT.priorityIsRequired` |
| Missing recipients | `RequestValidatorTest.missingRecipients`, `SubmissionIT.missingRecipients` |
| Unknown enum value | `RequestValidatorTest.unknownEnumValue`, `SubmissionIT.unknownEnumValue` |
| Expiry before schedule | `RequestValidatorTest.expiryBeforeSchedule`, `SubmissionIT.expiryBeforeSchedule` |
| Already expired | `SubmissionIT.alreadyExpired` |
| Missing idempotency key | `RequestValidatorTest.missingIdempotencyKey`, `SubmissionIT.missingIdempotencyKey` |
| Recipient limit exceeded | `RequestValidatorTest.recipientLimitExceeded`, `SubmissionIT.recipientLimitExceeded` |
| Rejection leaves only an audit trace | `SubmissionIT.rejectionLeavesOnlyAnAuditTrace` |
| Missing or invalid key | `ApiKeyAuthIT.missingOrInvalidKey` |
| Source system mismatch | `ApiKeyAuthIT.sourceSystemMismatch` |
| Future schedule | `SubmissionIT.futureSchedule` |

## webhook-delivery

| Scenario | Test |
|---|---|
| Signed webhook delivered | `WebhookDeliveryIT.signedWebhookDelivered` |
| Plain http target rejected | `WebhookTargetPolicyTest.plainHttpTargetRejected` |
| Private address target rejected | `WebhookTargetPolicyTest.privateAddressTargetRejected` |
| Private hosts allowed for development | `WebhookTargetPolicyTest.privateHostsAllowedForDevelopment` |
| Server error retried | `WebhookDeliveryIT.serverErrorRetried` |
| Unreachable endpoint retried | `WebhookDeliveryIT.unreachableEndpointRetried` |
| Slow endpoint times out | `WebhookDeliveryIT.slowEndpointTimesOut` |
| Webhook rate limit honored | `WebhookDeliveryIT.webhookRateLimitHonored` |
| Endpoint rejects credentials | `WebhookDeliveryIT.endpointRejectsCredentials` |
| Endpoint gone | `WebhookDeliveryIT.endpointGone` |
| Other client error not retried | `WebhookDeliveryIT.otherClientErrorNotRetried` |
| Redirect not followed | `WebhookDeliveryIT.redirectNotFollowed` |
| Signing secret required when enabled | `WebhookPropertiesTest.signingSecretRequiredWhenEnabled` |
| Queued webhook delivery fails while disabled | `WebhookDisabledIT.queuedWebhookDeliveryFailsWhileDisabled` |
