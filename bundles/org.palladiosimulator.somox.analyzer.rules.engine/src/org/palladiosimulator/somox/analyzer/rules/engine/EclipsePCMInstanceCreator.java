package org.palladiosimulator.somox.analyzer.rules.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.palladiosimulator.pcm.core.composition.AssemblyContext;
import org.palladiosimulator.pcm.repository.BasicComponent;
import org.palladiosimulator.pcm.repository.CollectionDataType;
import org.palladiosimulator.pcm.repository.CompositeComponent;
import org.palladiosimulator.pcm.repository.DataType;
import org.palladiosimulator.pcm.repository.OperationProvidedRole;
import org.palladiosimulator.pcm.repository.OperationRequiredRole;
import org.palladiosimulator.pcm.repository.ParameterModifier;
import org.palladiosimulator.pcm.repository.Repository;
import org.palladiosimulator.somox.analyzer.rules.blackboard.CompilationUnitWrapper;
import org.palladiosimulator.somox.analyzer.rules.blackboard.RuleEngineBlackboard;

import org.palladiosimulator.generator.fluent.exceptions.FluentApiException;
import org.palladiosimulator.generator.fluent.repository.api.Repo;
import org.palladiosimulator.generator.fluent.repository.factory.FluentRepositoryFactory;
import org.palladiosimulator.generator.fluent.repository.structure.components.BasicComponentCreator;
import org.palladiosimulator.generator.fluent.repository.structure.components.CompositeComponentCreator;
import org.palladiosimulator.generator.fluent.repository.structure.interfaces.OperationInterfaceCreator;
import org.palladiosimulator.generator.fluent.repository.structure.interfaces.OperationSignatureCreator;
import org.palladiosimulator.generator.fluent.repository.structure.internals.Primitive;
import org.palladiosimulator.generator.fluent.repository.structure.types.CompositeDataTypeCreator;

// TODO Bug-fix, probably
// Class to create a pcm instance out of all results from the detector class
public class EclipsePCMInstanceCreator {
    private static final Logger LOG = Logger.getLogger(EclipsePCMInstanceCreator.class);

    private static final String REPO_NAME = "Software Architecture Repository";
    private final FluentRepositoryFactory create;
    private final Repo repository;
    private final RuleEngineBlackboard blackboard;
    private final Map<String, CompositeDataTypeCreator> existingDataTypesMap;
    private final Map<String, DataType> existingCollectionDataTypes;
    private final Map<CompilationUnit, CompositeComponentCreator> unitCompositeCreators;
    private final Map<String, CompositeComponentCreator> ifaceCompositeCreators;
    private final Map<Composite, CompositeComponentCreator> compositeCreators;

    public EclipsePCMInstanceCreator(RuleEngineBlackboard blackboard) {
        existingDataTypesMap = new HashMap<>();
        existingCollectionDataTypes = new HashMap<>();
        this.unitCompositeCreators = new HashMap<>();
        this.ifaceCompositeCreators = new HashMap<>();
        this.compositeCreators = new HashMap<>();
        create = new FluentRepositoryFactory();
        repository = create.newRepository()
            .withName(REPO_NAME);
        this.blackboard = blackboard;
    }

    private class Pair<T1, T2> {
        private final T1 t1;
        private final T2 t2;

        public Pair(T1 t1, T2 t2) {
            this.t1 = t1;
            this.t2 = t2;
        }

        public T1 getT1() {
            return t1;
        }

        public T2 getT2() {
            return t2;
        }
    }

    private <K, V> void put(Map<K, List<V>> map, K key, V value) {
        if (!map.containsKey(key)) {
            map.put(key, new ArrayList<V>());
        }
        map.get(key)
            .add(value);
    }

    /**
     * Returns a PCM Repository model. It first creates the interfaces, then the components.
     *
     * @param mapping
     *            a mapping between microservice names and java model instances
     * @return the PCM repository model
     */
    public Repository createPCM(Map<String, List<CompilationUnitWrapper>> mapping) {
        final List<CompilationUnit> components = blackboard.getEclipsePCMDetector()
            .getComponents();
        final Map<String, List<IMethodBinding>> interfaces = blackboard.getEclipsePCMDetector()
            .getOperationInterfaces();
        final Set<Composite> composites = blackboard.getEclipsePCMDetector()
            .getCompositeComponents();

        createPCMInterfaces(interfaces);

        for (Composite composite : composites) {
            CompositeComponentCreator c = create.newCompositeComponent()
                .withName(composite.getName());
            composite.getParts()
                .forEach(x -> unitCompositeCreators.put(x, c));
            composite.getInternalInterfaces()
                .forEach(x -> ifaceCompositeCreators.put(x, c));
            compositeCreators.put(composite, c);

            for (String compRequiredIface : composite.getRequiredInterfaces()) {
                c.requires(create.fetchOfOperationInterface(compRequiredIface));
            }

            for (String compProvidedIface : composite.getProvidedInterfaces()) {
                c.provides(create.fetchOfOperationInterface(compProvidedIface));
            }
        }

        createPCMComponents(components);

        // Add assemblyConnections to composite component.
        for (Composite composite : composites) {
            CompositeComponentCreator c = compositeCreators.get(composite);
            CompositeComponent builtComp = (CompositeComponent) c.build();

            Map<String, List<Pair<OperationRequiredRole, AssemblyContext>>> innerRequirements = new HashMap<>();
            Map<String, List<Pair<OperationProvidedRole, AssemblyContext>>> innerProvisions = new HashMap<>();

            // Find requiredRoles
            builtComp.getAssemblyContexts__ComposedStructure()
                .stream()
                .forEach(x -> x.getEncapsulatedComponent__AssemblyContext()
                    .getRequiredRoles_InterfaceRequiringEntity()
                    .stream()
                    .map(OperationRequiredRole.class::cast)
                    .forEach(y -> put(innerRequirements, y.getRequiredInterface__OperationRequiredRole()
                        .getEntityName(), new Pair<OperationRequiredRole, AssemblyContext>(y, x))));

            // Find providedRoles
            builtComp.getAssemblyContexts__ComposedStructure()
                .stream()
                .forEach(x -> x.getEncapsulatedComponent__AssemblyContext()
                    .getProvidedRoles_InterfaceProvidingEntity()
                    .stream()
                    .map(OperationProvidedRole.class::cast)
                    .forEach(y -> put(innerProvisions, y.getProvidedInterface__OperationProvidedRole()
                        .getEntityName(), new Pair<OperationProvidedRole, AssemblyContext>(y, x))));

            // Match them up
            for (String internalIface : composite.getInternalInterfaces()) {
                for (Pair<OperationRequiredRole, AssemblyContext> r : innerRequirements.getOrDefault(internalIface,
                        List.of())) {
                    for (Pair<OperationProvidedRole, AssemblyContext> p : innerProvisions.getOrDefault(internalIface,
                            List.of())) {
                        if (!r.getT2()
                            .equals(p.getT2())) {
                            c.withAssemblyConnection(p.getT1(), p.getT2(), r.getT1(), r.getT2());
                        }
                    }
                }
            }

            Map<String, OperationRequiredRole> outerRequirements = new HashMap<>();
            builtComp.getRequiredRoles_InterfaceRequiringEntity()
                .stream()
                .map(OperationRequiredRole.class::cast)
                .forEach(x -> outerRequirements.put(x.getRequiredInterface__OperationRequiredRole()
                    .getEntityName(), x));

            for (String compRequiredIface : composite.getRequiredInterfaces()) {
                for (Pair<OperationRequiredRole, AssemblyContext> r : innerRequirements.getOrDefault(compRequiredIface,
                        List.of())) {
                    c.withRequiredDelegationConnection(r.getT2(), r.getT1(), outerRequirements.get(compRequiredIface));
                }
            }

            Map<String, OperationProvidedRole> outerProvisions = new HashMap<>();
            builtComp.getProvidedRoles_InterfaceProvidingEntity()
                .stream()
                .map(OperationProvidedRole.class::cast)
                .forEach(x -> outerProvisions.put(x.getProvidedInterface__OperationProvidedRole()
                    .getEntityName(), x));

            for (String compProvidedIface : composite.getProvidedInterfaces()) {
                for (Pair<OperationProvidedRole, AssemblyContext> r : innerProvisions.getOrDefault(compProvidedIface,
                        List.of())) {
                    c.withProvidedDelegationConnection(r.getT2(), r.getT1(), outerProvisions.get(compProvidedIface));
                }
            }

            repository.addToRepository(c);
        }

        return repository.createRepositoryNow();
    }

    private void createPCMInterfaces(Map<String, List<IMethodBinding>> interfaces) {
        interfaces.forEach((inter, methods) -> {
            LOG.info("Current PCM Interface: " + inter);

            OperationInterfaceCreator pcmInterface = create.newOperationInterface()
                .withName(inter);

            for (final IMethodBinding m : methods) {
                OperationSignatureCreator signature = create.newOperationSignature()
                    .withName(m.getName());

                // parameter type
                for (final ITypeBinding p : m.getParameterTypes()) {
                    signature = handleSignatureDataType(signature, p.getName(), p, p.getDimensions(), false);
                }

                // Return type: Cast Method Return Type to Variable
                // OrdinaryParameterImpl is sufficient since return types cannot be varargs.
                ITypeBinding rt = m.getReturnType();
                signature = handleSignatureDataType(signature, "", rt, rt.getDimensions(), true);

                pcmInterface.withOperationSignature(signature);
            }

            /*
             * // Add interfaces to its composite component, if it is an internal part of one.
             * CompositeComponentCreator c = unitCompositeCreators.get(inter); if (c != null) {
             * c.withAssemblyContext(builtIface); }
             */

            repository.addToRepository(pcmInterface);
        });
    }

    private void createPCMComponents(List<CompilationUnit> components) {
        for (final CompilationUnit comp : components) {
            AbstractTypeDeclaration firstTypeDecl = (AbstractTypeDeclaration) comp.types()
                .get(0);
            BasicComponentCreator pcmComp = create.newBasicComponent()
                .withName(wrapName(firstTypeDecl.resolveBinding()));

            final Set<String> providedInterfaces = blackboard.getEclipsePCMDetector()
                .getProvidedInterfaces(comp);

            for (String iface : providedInterfaces) {
                try {
                    pcmComp.provides(create.fetchOfOperationInterface(iface), "dummy name");
                } catch (FluentApiException e) {
                    // Add the interface on-demand if it was not in the model previously.
                    // This is necessary for interfaces that were not in the class path of the java
                    // parser.
                    create.newOperationInterface()
                        .withName(iface);
                    pcmComp.provides(create.fetchOfOperationInterface(iface), "dummy name");
                }
            }

            final Set<String> requiredIs = blackboard.getEclipsePCMDetector()
                .getRequiredInterfaces(comp);

            for (String requInter : requiredIs) {
                try {
                    pcmComp.requires(create.fetchOfOperationInterface(requInter), "dummy require name");
                } catch (FluentApiException e) {
                    // Add the interface on-demand if it was not in the model previously.
                    // This is necessary for interfaces that were not in the class path of the java
                    // parser.
                    create.newOperationInterface()
                        .withName(requInter);
                    pcmComp.requires(create.fetchOfOperationInterface(requInter), "dummy require name");
                }
            }
            BasicComponent builtComp = pcmComp.build();

            // Add component to its composite, if it is part of one.
            CompositeComponentCreator c = unitCompositeCreators.get(comp);
            if (c != null) {
                c.withAssemblyContext(builtComp);
            }

            blackboard.putRepositoryComponentLocation(builtComp, new CompilationUnitWrapper(comp));
            repository.addToRepository(builtComp);
        }
    }

    private static Primitive convertPrimitive(ITypeBinding primT) {
        switch (primT.getQualifiedName()) {
        case "boolean":
            return Primitive.BOOLEAN;
        case "byte":
            return Primitive.BYTE;
        case "char":
            return Primitive.CHAR;
        case "double":
            return Primitive.DOUBLE;
        case "float":
            // TODO replace with Primitive.FLOAT as soon as that works
            return Primitive.DOUBLE;
        case "int":
            return Primitive.INTEGER;
        case "long":
            return Primitive.LONG;
        case "short":
            // TODO replace with Primitive.SHORT as soon as that works
            return Primitive.INTEGER;
        default:
            return null;
        }
    }

    private OperationSignatureCreator handleSignatureDataType(OperationSignatureCreator signature, String varName,
            ITypeBinding variable, int varDimensions, boolean asReturnType) {

        // Parameter is a collection (extends Collection, is an array or a vararg)
        DataType collectionType = handleCollectionType(variable, varDimensions);
        if (collectionType != null) {
            if (asReturnType) {
                return signature.withReturnType(collectionType);
            }
            return signature.withParameter(varName, collectionType, ParameterModifier.IN);
        }

        // Check if type is a primitive type
        Primitive prim = handlePrimitive(variable);
        if (prim != null) {
            if (asReturnType) {
                return signature.withReturnType(prim);
            }
            return signature.withParameter(varName, prim, ParameterModifier.IN);
        }

        // Check if type is void (not part of pcm primitives)
        if ("void".equals(variable.getQualifiedName()) && asReturnType) {
            if (!create.containsDataType("Void")) {
                repository.addToRepository(create.newCompositeDataType()
                    .withName("Void"));
            }
            return signature.withReturnType(create.fetchOfDataType("Void"));
        }

        // Parameter is Composite Type
        DataType compositeType = handleCompositeType(variable);
        if (compositeType != null) {
            if (asReturnType) {
                return signature.withReturnType(compositeType);
            }
            return signature.withParameter(varName, compositeType, ParameterModifier.IN);
        }

        return null;
    }

    private DataType handleCollectionType(ITypeBinding ref, int dimensions) {
        // Base for the name of the collection data type
        String typeName = wrapName(ref);

        CollectionDataType collectionType = null;
        String collectionTypeName = null;

        if (dimensions != 0) {
            if (ref.isPrimitive()) {
                typeName = convertPrimitive(ref).name();
            }
            collectionTypeName = typeName;

            if (existingCollectionDataTypes.containsKey(collectionTypeName)) {
                return existingCollectionDataTypes.get(collectionTypeName);
            }

            collectionType = createCollectionWithTypeArg(collectionTypeName, ref, dimensions - 1);
        } else if (isCollectionType(ref) && (ref.getTypeArguments().length > 0)) {
            // TODO: I do not think this works properly for deeper collection types (e.g.
            // List<String>[]), especially the naming.
            typeName = wrapName(ref);
            ITypeBinding typeArg = ref.getTypeArguments()[0];
            String argumentTypeName = wrapName(typeArg);
            collectionTypeName = typeName + "<" + argumentTypeName + ">";

            LOG.info("Current Argument type name: " + argumentTypeName);

            if (existingCollectionDataTypes.containsKey(collectionTypeName)) {
                return existingCollectionDataTypes.get(collectionTypeName);
            }

            collectionType = createCollectionWithTypeArg(collectionTypeName, typeArg, typeArg.getDimensions());
        }
        if (collectionType != null) {
            existingCollectionDataTypes.put(collectionTypeName, collectionType);
            repository.addToRepository(collectionType);
        }
        return collectionType;
    }

    private CollectionDataType createCollectionWithTypeArg(String collectionTypeName, ITypeBinding typeArg,
            int typeArgDimensions) {
        // Type argument is primitive
        Primitive primitiveArg = handlePrimitive(typeArg);
        if (primitiveArg != null) {
            return create.newCollectionDataType(collectionTypeName, primitiveArg);
        }

        // Type argument is a collection again
        // A type argument cannot be a vararg, therefore it is "ordinary"
        DataType collectionArg = handleCollectionType(typeArg, typeArgDimensions);
        if (collectionArg != null) {
            return FluentRepositoryFactory.newCollectionDataType(collectionTypeName, collectionArg);
        }

        // Type argument is a composite data type
        DataType compositeArg = handleCompositeType(typeArg);
        if (compositeArg != null) {
            return FluentRepositoryFactory.newCollectionDataType(collectionTypeName, compositeArg);
        }

        return null;
    }

    private static boolean isCollectionType(ITypeBinding varClassifier) {

        List<ITypeBinding> refs = new ArrayList<>();

        if (varClassifier.isClass()) {
            refs.addAll(List.of(varClassifier.getInterfaces()));
        } else if (varClassifier.isInterface()) {
            if ("java.util.Collection".equals(varClassifier.getQualifiedName())) {
                return true;
            }
            refs.addAll(List.of(varClassifier.getInterfaces()));
        }

        for (ITypeBinding ref : refs) {
            if ("java.util.Collection".equals(ref.getQualifiedName())) {
                return true;
            }
        }

        return false;
    }

    private static Primitive handlePrimitive(ITypeBinding variable) {
        if (variable.isPrimitive()) {
            return convertPrimitive(variable);
        }
        // Parameter is String, which counts for PCM as Primitive
        if ("java.lang.String".equals(variable.getQualifiedName())) {
            return Primitive.STRING;
        }
        return null;
    }

    private DataType handleCompositeType(ITypeBinding ref) {
        String classifierName = wrapName(ref);

        if (!existingDataTypesMap.containsKey(classifierName)) {
            // TODO why is this commented out?
//            existingDataTypesMap.put(classifierName, createTypesRecursively(ref));
            existingDataTypesMap.put(classifierName, create.newCompositeDataType()
                .withName(classifierName));
            repository.addToRepository(existingDataTypesMap.get(classifierName));
        }

        return create.fetchOfCompositeDataType(classifierName);
    }

    private static String wrapName(ITypeBinding name) {
        String fullName = name.getQualifiedName()
            .replace(".", "_");
        // Erase type parameters in identifiers
        // TODO is this the right solution?
        if (fullName.contains("<")) {
            return fullName.substring(0, fullName.indexOf('<'));
        }
        return fullName;
    }
}
