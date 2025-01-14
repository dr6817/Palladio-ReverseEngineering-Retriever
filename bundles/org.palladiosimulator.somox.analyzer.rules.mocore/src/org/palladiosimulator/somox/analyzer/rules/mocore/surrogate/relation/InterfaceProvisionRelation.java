package org.palladiosimulator.somox.analyzer.rules.mocore.surrogate.relation;

import org.palladiosimulator.somox.analyzer.rules.mocore.surrogate.element.Component;
import org.palladiosimulator.somox.analyzer.rules.mocore.surrogate.element.Interface;

import tools.mdsd.mocore.framework.surrogate.Relation;
import tools.mdsd.mocore.framework.surrogate.Replaceable;

public class InterfaceProvisionRelation extends Relation<Component<?>, Interface> {
    public InterfaceProvisionRelation(Component<?> source, Interface destination, boolean isPlaceholder) {
        super(source, destination, isPlaceholder);
    }

    @Override
    public <U extends Replaceable> InterfaceProvisionRelation replace(U original, U replacement) {
        if (!this.includes(original)) {
            // TODO Add message to exception
            throw new IllegalArgumentException();
        }
        if (this.equals(original)) {
            return (InterfaceProvisionRelation) replacement;
        }
        Component<?> source = getSourceReplacement(original, replacement);
        Interface destination = getDestinationReplacement(original, replacement);
        return new InterfaceProvisionRelation(source, destination, this.isPlaceholder());
    }
}
