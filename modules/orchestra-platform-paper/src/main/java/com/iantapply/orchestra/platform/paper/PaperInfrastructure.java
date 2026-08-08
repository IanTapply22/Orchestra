package com.iantapply.orchestra.platform.paper;

import com.iantapply.orchestra.audit.AuditRepository;
import com.iantapply.orchestra.port.DefinitionRepository;
import com.iantapply.orchestra.port.DistributedLock;
import com.iantapply.orchestra.port.ExecutionRepository;
import com.iantapply.orchestra.velocity.ProxyCommandPublisher;

/** Infrastructure ports selected during Paper startup. */
record PaperInfrastructure(
        DefinitionRepository definitions,
        ExecutionRepository executions,
        DistributedLock locks,
        AuditRepository audit,
        ProxyCommandPublisher proxyCommands) {}
