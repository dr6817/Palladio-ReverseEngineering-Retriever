package org.palladiosimulator.somox.analyzer.rules.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.Stack;
import java.util.stream.Collectors;

import org.apache.log4j.Logger;
import org.palladiosimulator.somox.analyzer.rules.engine.MapMerger;

public class CompositeBuilder {

    private String name;
    private Set<ComponentBuilder> explicitParts = new HashSet<>();

    public CompositeBuilder(String name) {
        this.name = name;
    }

    public void addPart(ComponentBuilder componentBuilder) {
        explicitParts.add(componentBuilder);
    }

    public Composite construct(Collection<Component> components, Requirements compositeRequirements,
            Provisions compositeProvisions) {
        Logger.getLogger(getClass())
            .warn("Constructing composite component " + name);

        List<OperationInterface> allDependencies = new LinkedList<>();
        for (OperationInterface requirement : compositeRequirements) {
            allDependencies.add(requirement);
        }
        for (OperationInterface provision : compositeProvisions) {
            allDependencies.add(provision);
        }

        // Create and add all explicit parts.
        Set<Component> parts = explicitParts.stream()
            .map(x -> x.create(allDependencies))
            .collect(Collectors.toSet());

        Set<Component> remainingComponents = new HashSet<>(components);
        remainingComponents.removeAll(parts);
        Set<OperationInterface> internalInterfaces = new HashSet<>();

        int previousPartCount = 0;
        int previousInternalInterfaceCount = 0;
        do {
            previousPartCount = parts.size();
            previousInternalInterfaceCount = internalInterfaces.size();

            propagateRequirements(remainingComponents, compositeRequirements, compositeProvisions, parts,
                    internalInterfaces);
            propagateProvisions(remainingComponents, compositeRequirements, compositeProvisions, parts,
                    internalInterfaces);
        } while (parts.size() > previousPartCount && internalInterfaces.size() > previousInternalInterfaceCount);

        List<EntireInterface> requirements = new ArrayList<>();
        List<Map<String, List<Operation>>> provisions = new ArrayList<>();

        for (Component part : parts) {
            requirements.addAll(part.requirements()
                .get());
            provisions.add(part.provisions()
                .simplified());
        }

        Map<String, List<Operation>> simplifiedRequirements = MapMerger.merge(requirements.stream()
            .filter(x -> compositeRequirements.containsEntire(x))
            .map(EntireInterface::simplified)
            .collect(Collectors.toList()));

        Map<String, List<Operation>> simplifiedProvisions = MapMerger.merge(provisions)
            .entrySet()
            .stream()
            .filter(entry -> entry.getValue()
                .stream()
                .anyMatch(operation -> compositeProvisions.containsEntire(operation)))
            .collect(Collectors.toMap(entry -> entry.getKey(), entry -> entry.getValue()));

        return new Composite(name, parts, simplifiedRequirements, simplifiedProvisions, internalInterfaces);
    }

    // Writes to remainingComopnents, parts, and internalInterfaces.
    private static void propagateProvisions(Set<Component> remainingComponents,
            final Requirements compositeRequirements, final Provisions compositeProvisions, Set<Component> parts,
            Set<OperationInterface> internalInterfaces) {

        List<Component> newParts = new LinkedList<>();
        for (Component providingPart : parts) {
            List<OperationInterface> traversedInterfaces = findRequiringComponents(remainingComponents,
                    compositeRequirements, compositeProvisions, newParts, providingPart);

            Queue<OperationInterface> sortedInterfaces = new PriorityQueue<>(traversedInterfaces);
            while (!sortedInterfaces.isEmpty()) {
                OperationInterface iface = sortedInterfaces.poll();
                boolean isRoot = true;
                for (OperationInterface rootInterface : internalInterfaces) {
                    if (iface.isPartOf(rootInterface)) {
                        isRoot = false;
                        break;
                    }
                    if (rootInterface.isPartOf(iface)) {
                        internalInterfaces.remove(rootInterface);
                        break;
                    }
                }
                if (isRoot) {
                    internalInterfaces.add(iface);
                }
            }
        }

        parts.addAll(newParts);
    }

    // Writes to remainingComopnents, parts, and internalInterfaces.
    private static void propagateRequirements(Set<Component> remainingComponents,
            final Requirements compositeRequirements, final Provisions compositeProvisions, Set<Component> parts,
            Set<OperationInterface> internalInterfaces) {

        List<Component> newParts = new LinkedList<>();
        for (Component requiringPart : parts) {
            List<OperationInterface> traversedInterfaces = findProvidingComponents(remainingComponents,
                    compositeRequirements, compositeProvisions, newParts, requiringPart);

            Queue<OperationInterface> sortedInterfaces = new PriorityQueue<>(traversedInterfaces);
            while (!sortedInterfaces.isEmpty()) {
                OperationInterface iface = sortedInterfaces.poll();
                boolean isRoot = true;
                for (OperationInterface rootInterface : internalInterfaces) {
                    if (iface.isPartOf(rootInterface)) {
                        isRoot = false;
                        break;
                    }
                    if (rootInterface.isPartOf(iface)) {
                        internalInterfaces.remove(rootInterface);
                        break;
                    }
                }
                if (isRoot) {
                    internalInterfaces.add(iface);
                }
            }
        }

        parts.addAll(newParts);
    }

    // May remove components from remainingComponents.
    private static List<OperationInterface> findRequiringComponents(Set<Component> remainingComponents,
            final Requirements compositeRequirements, final Provisions compositeProvisions, List<Component> newParts,
            final Component providingComponent) {

        Stack<OperationInterface> provisions = new Stack<>();
        providingComponent.provisions()
            .get()
            .stream()
            // Do not include interfaces required or provided by composites.
            // This ensures that those composites do not become part of this composite.
            .filter(x -> !compositeRequirements.containsEntire(x))
            .filter(x -> !compositeProvisions.containsEntire(x))
            .forEach(provisions::add);

        List<OperationInterface> traversedOperations = new ArrayList<>();
        while (!provisions.isEmpty()) {
            OperationInterface provision = provisions.pop();
            Set<Component> requiringComponents = remainingComponents.stream()
                .filter(x -> x.requirements()
                    .containsPartOf(provision))
                .filter(x -> !providingComponent.equals(x))
                .collect(Collectors.toSet());

            // Skip this provision if no unit requires it.
            if (!requiringComponents.isEmpty()) {
                traversedOperations.add(provision);
                remainingComponents.removeAll(requiringComponents);
                newParts.addAll(requiringComponents);
            }
        }

        return traversedOperations;
    }

    // May remove components from remainingComponents.
    private static List<OperationInterface> findProvidingComponents(Set<Component> remainingComponents,
            final Requirements compositeRequirements, final Provisions compositeProvisions, List<Component> newParts,
            final Component requiringComponent) {

        Stack<OperationInterface> requirements = new Stack<>();
        requiringComponent.requirements()
            .get()
            .stream()
            // Do not include interfaces required or provided by composites.
            // This ensures that those composites do not become part of this composite.
            .filter(x -> !compositeRequirements.containsEntire(x))
            .filter(x -> !compositeProvisions.containsEntire(x))
            .forEach(requirements::add);

        List<OperationInterface> traversedOperations = new ArrayList<>();
        while (!requirements.isEmpty()) {
            OperationInterface requirement = requirements.pop();
            Set<Component> providingComponents = remainingComponents.stream()
                .filter(x -> x.provisions()
                    .containsPartOf(requirement))
                .filter(x -> !requiringComponent.equals(x))
                .collect(Collectors.toSet());

            // Skip this requirement if no unit provides it.
            if (!providingComponents.isEmpty()) {
                traversedOperations.add(requirement);
                remainingComponents.removeAll(providingComponents);
                newParts.addAll(providingComponents);
            }
        }

        return traversedOperations;
    }

    @Override
    public int hashCode() {
        return Objects.hash(explicitParts, name);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        CompositeBuilder other = (CompositeBuilder) obj;
        return Objects.equals(explicitParts, other.explicitParts) && Objects.equals(name, other.name);
    }
}
