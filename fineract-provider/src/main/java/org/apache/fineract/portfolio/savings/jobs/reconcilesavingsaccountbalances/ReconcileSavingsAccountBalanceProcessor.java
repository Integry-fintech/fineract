/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0.
 */
package org.apache.fineract.portfolio.savings.jobs.reconcilesavingsaccountbalances;

import lombok.RequiredArgsConstructor;
import org.apache.fineract.portfolio.savings.domain.SavingsAccount;
import org.apache.fineract.portfolio.savings.domain.SavingsAccountAssembler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ReconcileSavingsAccountBalanceProcessor {

    private final SavingsAccountAssembler savingsAccountAssembler;

    /**
     * Rebuilds the persisted transaction-derived summary while holding the account lock. The managed account is flushed
     * by transaction commit only when JPA detects changed summary fields.
     *
     * @return {@code true} when at least one persisted summary field changed
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reconcile(final Long savingsId) {
        final SavingsAccount account = this.savingsAccountAssembler.assembleForBalanceReconciliation(savingsId);
        return account.reconcileTransactionDerivedSummary();
    }
}
