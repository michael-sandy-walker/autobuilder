package com.walpro.autobuilder.core.services;

import java.util.List;

/** T4-EN: the project did not start for want of services nothing has been said about yet -- ask the operator. */
public class ServicesNeeded extends RuntimeException {

    private final transient List<ServiceProposal> proposals;

    public ServicesNeeded(List<ServiceProposal> proposals) {
        super("the project needs services to start: " + proposals.stream().map(ServiceProposal::name).toList());
        this.proposals = List.copyOf(proposals);
    }

    public List<ServiceProposal> proposals() {
        return proposals;
    }
}
