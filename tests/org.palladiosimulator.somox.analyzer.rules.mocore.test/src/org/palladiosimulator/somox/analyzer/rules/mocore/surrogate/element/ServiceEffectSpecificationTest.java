package org.palladiosimulator.somox.analyzer.rules.mocore.surrogate.element;

import org.palladiosimulator.pcm.seff.ResourceDemandingSEFF;
import org.palladiosimulator.somox.analyzer.rules.mocore.utility.ElementFactory;

import tools.mdsd.mocore.framework.surrogate.ElementTest;

public class ServiceEffectSpecificationTest
        extends ElementTest<ServiceEffectSpecification, ResourceDemandingSEFF> {
    @Override
    protected ServiceEffectSpecification createElement(ResourceDemandingSEFF value, boolean isPlaceholder) {
        return new ServiceEffectSpecification(value, isPlaceholder);
    }

    @Override
    protected ResourceDemandingSEFF getUniqueValue() {
        return ElementFactory.createUniqueServiceEffectSpecification(false).getValue();
    }

    @Override
    protected ServiceEffectSpecification getUniqueNonPlaceholder() {
        return new ServiceEffectSpecification(getUniqueValue(), false);
    }

    @Override
    protected ServiceEffectSpecification getPlaceholderOf(ServiceEffectSpecification replaceable) {
        return new ServiceEffectSpecification(replaceable.getValue(), true);
    }
}
