package com.cdp.journey;

/** approve（備註）、reject（原因，必填）、withdraw（原因）、stop（原因，必填）共用。 */
public record ReasonRequest(String reason) {}
