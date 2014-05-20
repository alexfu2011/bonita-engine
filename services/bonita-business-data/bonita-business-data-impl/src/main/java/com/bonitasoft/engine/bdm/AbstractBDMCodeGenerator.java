/*******************************************************************************
 * Copyright (C) 2014 Bonitasoft S.A.
 * Bonitasoft is a trademark of Bonitasoft SA.
 * This software file is BONITASOFT CONFIDENTIAL. Not For Distribution.
 * For commercial licensing information, contact:
 * Bonitasoft, 32 rue Gustave Eiffel – 38000 Grenoble
 * or Bonitasoft US, 51 Federal Street, Suite 305, San Francisco, CA 94107
 *******************************************************************************/
package com.bonitasoft.engine.bdm;

import static com.bonitasoft.engine.bdm.validator.rule.QueryParameterValidationRule.FORBIDDEN_PARAMETER_NAMES;

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.persistence.CascadeType;
import javax.persistence.Column;
import javax.persistence.ElementCollection;
import javax.persistence.Entity;
import javax.persistence.FetchType;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.Lob;
import javax.persistence.ManyToOne;
import javax.persistence.NamedQueries;
import javax.persistence.NamedQuery;
import javax.persistence.OneToMany;
import javax.persistence.Table;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import javax.persistence.Version;

import org.apache.commons.lang3.text.WordUtils;
import org.hibernate.annotations.IndexColumn;

import com.bonitasoft.engine.bdm.dao.BusinessObjectDAO;
import com.bonitasoft.engine.bdm.model.BusinessObject;
import com.bonitasoft.engine.bdm.model.BusinessObjectModel;
import com.bonitasoft.engine.bdm.model.Index;
import com.bonitasoft.engine.bdm.model.Query;
import com.bonitasoft.engine.bdm.model.QueryParameter;
import com.bonitasoft.engine.bdm.model.UniqueConstraint;
import com.bonitasoft.engine.bdm.model.field.Field;
import com.bonitasoft.engine.bdm.model.field.FieldType;
import com.bonitasoft.engine.bdm.model.field.RelationField;
import com.bonitasoft.engine.bdm.model.field.RelationField.Type;
import com.bonitasoft.engine.bdm.model.field.SimpleField;
import com.bonitasoft.engine.bdm.validator.BusinessObjectModelValidator;
import com.bonitasoft.engine.bdm.validator.ValidationStatus;
import com.sun.codemodel.JAnnotationArrayMember;
import com.sun.codemodel.JAnnotationUse;
import com.sun.codemodel.JBlock;
import com.sun.codemodel.JClass;
import com.sun.codemodel.JClassAlreadyExistsException;
import com.sun.codemodel.JDefinedClass;
import com.sun.codemodel.JExpr;
import com.sun.codemodel.JFieldVar;
import com.sun.codemodel.JForEach;
import com.sun.codemodel.JMethod;
import com.sun.codemodel.JMod;
import com.sun.codemodel.JType;
import com.sun.codemodel.JVar;

/**
 * @author Romain Bioteau
 * @author Matthieu Chaffotte
 */
public abstract class AbstractBDMCodeGenerator extends CodeGenerator {

    private static final String DAO_SUFFIX = "DAO";

    protected static final String DAO_IMPL_SUFFIX = "DAOImpl";

    private final BusinessObjectModel bom;

    public AbstractBDMCodeGenerator(final BusinessObjectModel bom) {
        super();
        if (bom == null) {
            throw new IllegalArgumentException("bom is null");
        }
        this.bom = bom;
    }

    public void buildJavaModelFromBom() throws JClassAlreadyExistsException, ClassNotFoundException {
        for (final BusinessObject bo : bom.getBusinessObjects()) {
            final JDefinedClass entity = addEntity(bo);
            addDAO(bo, entity);
        }
    }

    protected abstract void addDAO(final BusinessObject bo, JDefinedClass entity) throws JClassAlreadyExistsException, ClassNotFoundException;

    public JDefinedClass addEntity(final BusinessObject bo) throws JClassAlreadyExistsException {
        final String qualifiedName = bo.getQualifiedName();
        validateClassNotExistsInRuntime(qualifiedName);

        JDefinedClass entityClass = addClass(qualifiedName);
        entityClass = addInterface(entityClass, com.bonitasoft.engine.bdm.Entity.class.getName());
        entityClass.javadoc().add(bo.getDescription());

        final JAnnotationUse entityAnnotation = addAnnotation(entityClass, Entity.class);
        entityAnnotation.param("name", entityClass.name());

        addIndexAnnotations(bo, entityClass);
        addUniqueConstraintAnnotations(bo, entityClass);
        addQueriesAnnotation(bo, entityClass);

        addFieldsAndMethods(bo, entityClass);

        addDefaultConstructor(entityClass);
        addCopyConstructor(entityClass, bo);

        addEqualsMethod(entityClass);
        addHashCodeMethod(entityClass);

        return entityClass;
    }

    private void addFieldsAndMethods(final BusinessObject bo, final JDefinedClass entityClass) throws JClassAlreadyExistsException {
        addPersistenceIdFieldAndAccessors(entityClass);
        addPersistenceVersionFieldAndAccessors(entityClass);

        for (final Field field : bo.getFields()) {
            final JFieldVar fieldVar = addField(entityClass, field);
            addAccessors(entityClass, fieldVar);
            addModifiers(entityClass, field);
        }
    }

    private void addQueriesAnnotation(final BusinessObject bo, final JDefinedClass entityClass) {
        final JAnnotationUse namedQueriesAnnotation = addAnnotation(entityClass, NamedQueries.class);
        final JAnnotationArrayMember valueArray = namedQueriesAnnotation.paramArray("value");

        // Add provided queries
        for (final Query providedQuery : BDMQueryUtil.createProvidedQueriesForBusinessObject(bo)) {
            addNamedQuery(entityClass, valueArray, providedQuery.getName(), providedQuery.getContent());
        }

        // Add custom queries
        for (final Query query : bo.getQueries()) {
            addNamedQuery(entityClass, valueArray, query.getName(), query.getContent());
        }
    }

    private void addUniqueConstraintAnnotations(final BusinessObject bo, final JDefinedClass entityClass) {
        final JAnnotationUse tableAnnotation = addAnnotation(entityClass, Table.class);
        tableAnnotation.param("name", entityClass.name().toUpperCase());

        final List<UniqueConstraint> uniqueConstraints = bo.getUniqueConstraints();
        if (!uniqueConstraints.isEmpty()) {
            final JAnnotationArrayMember uniqueConstraintsArray = tableAnnotation.paramArray("uniqueConstraints");
            for (final UniqueConstraint uniqueConstraint : uniqueConstraints) {
                final JAnnotationUse uniqueConstraintAnnotatation = uniqueConstraintsArray.annotate(javax.persistence.UniqueConstraint.class);
                uniqueConstraintAnnotatation.param("name", uniqueConstraint.getName().toUpperCase());
                final JAnnotationArrayMember columnNamesParamArray = uniqueConstraintAnnotatation.paramArray("columnNames");
                for (final String fieldName : uniqueConstraint.getFieldNames()) {
                    columnNamesParamArray.param(fieldName.toUpperCase());
                }
            }
        }
    }

    private void addIndexAnnotations(final BusinessObject bo, final JDefinedClass entityClass) {
        final List<Index> indexes = bo.getIndexes();
        if (indexes != null && !indexes.isEmpty()) {
            final JAnnotationUse hibTabAnnotation = addAnnotation(entityClass, org.hibernate.annotations.Table.class);
            hibTabAnnotation.param("appliesTo", entityClass.name().toUpperCase());
            final JAnnotationArrayMember indexesArray = hibTabAnnotation.paramArray("indexes");
            for (final Index index : indexes) {
                final JAnnotationUse indexAnnotation = indexesArray.annotate(org.hibernate.annotations.Index.class);
                indexAnnotation.param("name", index.getName().toUpperCase());
                final JAnnotationArrayMember columnParamArray = indexAnnotation.paramArray("columnNames");
                for (final String fieldName : index.getFieldNames()) {
                    columnParamArray.param(fieldName.toUpperCase());
                }
            }
        }
    }

    private void addNamedQuery(final JDefinedClass entityClass, final JAnnotationArrayMember valueArray, final String name, final String content) {
        final JAnnotationUse nameQueryAnnotation = valueArray.annotate(NamedQuery.class);
        nameQueryAnnotation.param("name", entityClass.name() + "." + name);
        nameQueryAnnotation.param("query", content);
    }

    private void validateClassNotExistsInRuntime(final String qualifiedName) {
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        boolean alreadyInRuntime = true;
        try {
            contextClassLoader.loadClass(qualifiedName);
        } catch (final ClassNotFoundException e) {
            alreadyInRuntime = false;
        }
        if (alreadyInRuntime) {
            throw new IllegalArgumentException("Class " + qualifiedName + " already exists in target runtime environment.");
        }
    }

    protected void addCopyConstructor(final JDefinedClass entityClass, final BusinessObject bo) {
        final JMethod copyConstructor = entityClass.constructor(JMod.PUBLIC);
        final JVar param = copyConstructor.param(entityClass, WordUtils.uncapitalize(entityClass.name()));
        final JBlock copyBody = copyConstructor.body();
        copyBody.assign(JExpr.refthis(Field.PERSISTENCE_ID), JExpr.invoke(JExpr.ref(param.name()), "getPersistenceId"));
        copyBody.assign(JExpr.refthis(Field.PERSISTENCE_VERSION), JExpr.invoke(JExpr.ref(param.name()), "getPersistenceVersion"));
        for (final Field field : bo.getFields()) {
            if (field.isCollection() != null && field.isCollection()) {
                final JClass fieldClass = toJavaClass(field);
                final JClass arrayListFieldClazz = narrowClass(ArrayList.class, fieldClass);
                if (field instanceof SimpleField) {
                    copyBody.assign(JExpr.refthis(field.getName()),
                            JExpr._new(arrayListFieldClazz).arg(JExpr.invoke(JExpr.ref(param.name()), getGetterName(field))));
                } else {
                    copyBody.assign(JExpr.refthis(field.getName()), JExpr._new(arrayListFieldClazz));
                    final JForEach forEach = copyBody.forEach(fieldClass, "i", JExpr.invoke(JExpr.ref(param.name()), getGetterName(field)));
                    forEach.body().invoke(JExpr.refthis(field.getName()), "add").arg(JExpr._new(fieldClass).arg(forEach.var()));
                }
            } else {
                copyBody.assign(JExpr.refthis(field.getName()), JExpr.invoke(JExpr.ref(param.name()), getGetterName(field)));
            }
        }
    }

    public void addPersistenceIdFieldAndAccessors(final JDefinedClass entityClass) throws JClassAlreadyExistsException {
        final JFieldVar idFieldVar = addField(entityClass, Field.PERSISTENCE_ID, toJavaClass(FieldType.LONG));
        addAnnotation(idFieldVar, Id.class);
        addAnnotation(idFieldVar, GeneratedValue.class);
        addAccessors(entityClass, idFieldVar);
    }

    public void addPersistenceVersionFieldAndAccessors(final JDefinedClass entityClass) throws JClassAlreadyExistsException {
        final JFieldVar versionField = addField(entityClass, Field.PERSISTENCE_VERSION, toJavaClass(FieldType.LONG));
        addAnnotation(versionField, Version.class);
        addAccessors(entityClass, versionField);
    }

    public JFieldVar addField(final JDefinedClass entityClass, final Field field) throws JClassAlreadyExistsException {
        JFieldVar fieldVar = null;
        if (field.isCollection()) {
            fieldVar = addListField(entityClass, field);
        } else {
            fieldVar = addField(entityClass, field.getName(), toJavaClass(field));
        }
        annotateField(field, fieldVar);
        return fieldVar;
    }
    
    private void annotateField(final Field field, JFieldVar fieldVar) {
        if (field instanceof SimpleField) {
            annotateSimpleField((SimpleField) field, fieldVar);
        } else if (field instanceof RelationField) {
            annotateRelationField((RelationField) field, fieldVar);
        }
    }
    
    private void annotateRelationField(final RelationField rfield, JFieldVar fieldVar) {
        JAnnotationUse relation = null;
        if (rfield.isCollection()) {
            relation = addAnnotation(fieldVar, OneToMany.class);
            // add IndexColumn to add the list behaviour with EAGER
            final JAnnotationUse index = addAnnotation(fieldVar, IndexColumn.class);
            index.param("name", "IDX_" + fieldVar.name());
            final JAnnotationUse joinColumn = addAnnotation(fieldVar, JoinColumn.class);
            joinColumn.param("nullable", rfield.isNullable());
        } else {
            relation = addAnnotation(fieldVar, ManyToOne.class);
            relation.param("optional", rfield.isNullable());
        }
        relation.param("fetch", FetchType.EAGER);
        if (rfield.getType() == Type.COMPOSITION) {
            final JAnnotationArrayMember cascade = relation.paramArray("cascade");
            cascade.param(CascadeType.ALL);
        }
    }

    private void annotateSimpleField(final SimpleField sfield, JFieldVar fieldVar) {
        if (sfield.isCollection()) {
            final JAnnotationUse collectionAnnotation = addAnnotation(fieldVar, ElementCollection.class);
            collectionAnnotation.param("fetch", FetchType.EAGER);
        }
        final JAnnotationUse columnAnnotation = addAnnotation(fieldVar, Column.class);
        columnAnnotation.param("name", sfield.getName().toUpperCase());
        columnAnnotation.param("nullable", sfield.isNullable());

        if (sfield.getType() == FieldType.DATE) {
            final JAnnotationUse temporalAnnotation = addAnnotation(fieldVar, Temporal.class);
            temporalAnnotation.param("value", TemporalType.TIMESTAMP);
        } else if (FieldType.TEXT == sfield.getType()) {
            addAnnotation(fieldVar, Lob.class);
        } else if (FieldType.STRING == sfield.getType() && sfield.getLength() != null && sfield.getLength() > 0) {
            columnAnnotation.param("length", sfield.getLength());
        }
    }

    public void addAccessors(final JDefinedClass entityClass, final JFieldVar fieldVar) throws JClassAlreadyExistsException {
        addSetter(entityClass, fieldVar);
        addGetter(entityClass, fieldVar);
    }

    protected void addModifiers(final JDefinedClass entityClass, final Field field) throws JClassAlreadyExistsException {
        final Boolean collection = field.isCollection();
        if (collection != null && collection) {
            addAddMethod(entityClass, field);
            addRemoveMethod(entityClass, field);
        }
    }

    @Override
    public void generate(final File destDir) throws IOException, JClassAlreadyExistsException, BusinessObjectModelValidationException, ClassNotFoundException {
        final BusinessObjectModelValidator validator = new BusinessObjectModelValidator();
        final ValidationStatus validationStatus = validator.validate(bom);
        if (!validationStatus.isOk()) {
            throw new BusinessObjectModelValidationException(validationStatus);
        }
        buildJavaModelFromBom();
        super.generate(destDir);
    }

    protected JDefinedClass createDAOInterface(final BusinessObject bo, final JDefinedClass entity) throws JClassAlreadyExistsException, ClassNotFoundException {
        final String daoInterfaceClassName = toDaoInterfaceClassname(bo);
        final JDefinedClass daoInterface = addInterface(daoInterfaceClassName);
        addInterface(daoInterface, BusinessObjectDAO.class.getName());

        // Add method signature in interface for provided queries
        for (final Query q : BDMQueryUtil.createProvidedQueriesForBusinessObject(bo)) {
            createMethodForQuery(entity, daoInterface, q);
        }

        // Add method signature in interface for custom queries
        for (final Query q : bo.getQueries()) {
            createMethodForQuery(entity, daoInterface, q);
        }

        return daoInterface;
    }

    public JMethod createMethodForQuery(final JDefinedClass entity, final JDefinedClass targetClass, final Query query) throws ClassNotFoundException {
        final String methodName = query.getName();
        final JMethod queryMethod = createQueryMethod(entity, targetClass, methodName, query.getReturnType());
        for (final QueryParameter param : query.getQueryParameters()) {
            queryMethod.param(getModel().ref(param.getClassName()), param.getName());
        }
        addOptionalPaginationParameters(queryMethod, query.getReturnType());
        return queryMethod;
    }

    private JMethod createQueryMethod(final JDefinedClass entity, final JDefinedClass targetClass, final String name, final String returnTypeName)
            throws ClassNotFoundException {
        JType returnType;
        if (returnTypeName.equals(entity.fullName())) {
            returnType = entity;
        } else {
            returnType = getModel().ref(returnTypeName);
        }
        final JClass collectionType = getModel().ref(Collection.class.getName());
        if (returnType instanceof JClass && collectionType.isAssignableFrom((JClass) returnType)) {
            returnType = ((JClass) returnType).narrow(entity);
        }
        return addMethodSignature(targetClass, name, returnType);
    }

    public String toDaoInterfaceClassname(final BusinessObject bo) {
        return bo.getQualifiedName() + DAO_SUFFIX;
    }

    private void addOptionalPaginationParameters(final JMethod queryMethod, final String returnType) throws ClassNotFoundException {
        if (List.class.getName().equals(returnType)) {
            for (final String param : FORBIDDEN_PARAMETER_NAMES) {
                queryMethod.param(getModel().ref(int.class.getName()), param);
            }
        }
    }

    protected void addQueryParameters(final JMethod method, final JBlock body, final JClass mapClass, final JClass hashMapClass, final JVar commandParametersRef) {
        if (!method.params().isEmpty()) {
            final JVar queryParametersRef = body.decl(mapClass, "queryParameters", JExpr._new(hashMapClass));
            for (final JVar param : method.params()) {
                if (!FORBIDDEN_PARAMETER_NAMES.contains(param.name())) {
                    body.invoke(queryParametersRef, "put").arg(JExpr.lit(param.name())).arg(param);
                }
            }
            body.invoke(commandParametersRef, "put").arg(JExpr.lit("queryParameters")).arg(JExpr.cast(getModel().ref(Serializable.class), queryParametersRef));
        }
    }

}
