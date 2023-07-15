/*
 * Copyright 2017-2023 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.openapi.visitor;

import java.io.IOException;
import java.io.Reader;
import java.io.Serial;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.processing.SupportedOptions;

import io.micronaut.context.ApplicationContextConfiguration;
import io.micronaut.context.DefaultApplicationContextBuilder;
import io.micronaut.context.env.Environment;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.convert.DefaultMutableConversionService;
import io.micronaut.core.convert.MutableConversionService;
import io.micronaut.core.io.scan.ClassPathResourceLoader;
import io.micronaut.core.io.scan.DefaultClassPathResourceLoader;
import io.micronaut.core.naming.conventions.StringConvention;
import io.micronaut.core.type.Argument;
import io.micronaut.core.type.GenericArgument;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.ast.ClassElement;
import io.micronaut.inject.ast.Element;
import io.micronaut.inject.ast.ElementModifier;
import io.micronaut.inject.ast.ElementQuery;
import io.micronaut.inject.visitor.TypeElementVisitor;
import io.micronaut.inject.visitor.VisitorContext;
import io.micronaut.inject.writer.GeneratedFile;
import io.micronaut.openapi.postprocessors.JacksonDiscriminatorPostProcessor;
import io.micronaut.openapi.postprocessors.OpenApiOperationsPostProcessor;
import io.micronaut.openapi.view.OpenApiViewConfig;
import io.micronaut.openapi.visitor.group.EndpointInfo;
import io.micronaut.openapi.visitor.group.GroupProperties;
import io.micronaut.openapi.visitor.group.GroupProperties.PackageProperties;
import io.micronaut.openapi.visitor.group.OpenApiInfo;
import io.micronaut.openapi.visitor.group.RouterVersioningProperties;
import io.micronaut.openapi.visitor.security.InterceptUrlMapConverter;
import io.micronaut.openapi.visitor.security.InterceptUrlMapPattern;
import io.micronaut.openapi.visitor.security.SecurityProperties;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.security.SecurityScheme;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import static io.micronaut.openapi.visitor.SchemaUtils.EMPTY_SIMPLE_SCHEMA;
import static io.micronaut.openapi.visitor.SchemaUtils.TYPE_OBJECT;
import static io.micronaut.openapi.visitor.SchemaUtils.getOperationOnPathItem;
import static io.micronaut.openapi.visitor.SchemaUtils.setOperationOnPathItem;
import static io.micronaut.openapi.visitor.group.RouterVersioningProperties.DEFAULT_HEADER_NAME;
import static io.micronaut.openapi.visitor.group.RouterVersioningProperties.DEFAULT_PARAMETER_NAME;
import static io.swagger.v3.oas.models.Components.COMPONENTS_SCHEMAS_REF;

/**
 * Visits the application class.
 *
 * @author graemerocher
 * @since 1.0
 */
@SupportedOptions({
    OpenApiApplicationVisitor.MICRONAUT_OPENAPI_ENABLED,
    OpenApiApplicationVisitor.MICRONAUT_OPENAPI_CONTEXT_SERVER_PATH,
    OpenApiApplicationVisitor.MICRONAUT_OPENAPI_PROPERTY_NAMING_STRATEGY,
    OpenApiApplicationVisitor.MICRONAUT_OPENAPI_VIEWS_SPEC,
    OpenApiApplicationVisitor.MICRONAUT_OPENAPI_FILENAME,
    OpenApiApplicationVisitor.MICRONAUT_OPENAPI_JSON_FORMAT,
    OpenApiApplicationVisitor.MICRONAUT_OPENAPI_ENVIRONMENTS,
    OpenApiApplicationVisitor.MICRONAUT_ENVIRONMENT_ENABLED,
    OpenApiApplicationVisitor.MICRONAUT_OPENAPI_FIELD_VISIBILITY_LEVEL,
    OpenApiApplicationVisitor.MICRONAUT_CONFIG_FILE_LOCATIONS,
    OpenApiApplicationVisitor.MICRONAUT_OPENAPI_TARGET_FILE,
    OpenApiApplicationVisitor.MICRONAUT_OPENAPI_VIEWS_DEST_DIR,
    OpenApiApplicationVisitor.MICRONAUT_OPENAPI_ADDITIONAL_FILES,
    OpenApiApplicationVisitor.MICRONAUT_OPENAPI_CONFIG_FILE,
    OpenApiApplicationVisitor.MICRONAUT_OPENAPI_SECURITY_ENABLED,
    OpenApiApplicationVisitor.MICRONAUT_OPENAPI_VERSIONING_ENABLED,
})
public class OpenApiApplicationVisitor extends AbstractOpenApiVisitor implements TypeElementVisitor<OpenAPIDefinition, Object> {

    /**
     * System property that enables or disables open api annotation processing.
     * <br>
     * Default: true
     */
    public static final String MICRONAUT_OPENAPI_ENABLED = "micronaut.openapi.enabled";
    /**
     * System property that enables setting the open api config file.
     */
    public static final String MICRONAUT_OPENAPI_CONFIG_FILE = "micronaut.openapi.config.file";
    /**
     * Prefix for expandable properties.
     */
    public static final String MICRONAUT_OPENAPI_EXPAND_PREFIX = "micronaut.openapi.expand.";
    /**
     * System property for server context path.
     */
    public static final String MICRONAUT_OPENAPI_CONTEXT_SERVER_PATH = "micronaut.openapi.server.context.path";
    /**
     * System property for naming strategy. One jackson PropertyNamingStrategy.
     */
    public static final String MICRONAUT_OPENAPI_PROPERTY_NAMING_STRATEGY = "micronaut.openapi.property.naming.strategy";
    /**
     * System property for views specification.
     */
    public static final String MICRONAUT_OPENAPI_VIEWS_SPEC = "micronaut.openapi.views.spec";
    /**
     * System property that enables setting the target file to write to.
     */
    public static final String MICRONAUT_OPENAPI_TARGET_FILE = "micronaut.openapi.target.file";
    /**
     * System property that specifies the path where the generated UI elements will be located.
     */
    public static final String MICRONAUT_OPENAPI_VIEWS_DEST_DIR = "micronaut.openapi.views.dest.dir";
    /**
     * System property that specifies the location of additional swagger YAML and JSON files to read from.
     */
    public static final String MICRONAUT_OPENAPI_ADDITIONAL_FILES = "micronaut.openapi.additional.files";
    /**
     * System property that specifies the default security schema name, if it's not specified by annotation SecurityScheme.
     */
    public static final String MICRONAUT_OPENAPI_SECURITY_DEFAULT_SCHEMA_NAME = "micronaut.openapi.security.default-schema-name";
    /**
     * System property that specifies the schema classes fields visibility level. By default, only public fields visibile.
     * <p>
     * Available values:
     * </p>
     * PRIVATE
     * PACKAGE
     * PROTECTED
     * PUBLIC
     */
    public static final String MICRONAUT_OPENAPI_FIELD_VISIBILITY_LEVEL = "micronaut.openapi.field.visibility.level";
    /**
     * Default openapi config file.
     */
    public static final String OPENAPI_CONFIG_FILE = "openapi.properties";
    /**
     * The name of the entry for Endpoint class tags in the context.
     */
    public static final String MICRONAUT_OPENAPI_ENDPOINT_CLASS_TAGS = "micronaut.openapi.endpoint.class.tags";
    /**
     * The name of the entry for Endpoint servers in the context.
     */
    public static final String MICRONAUT_OPENAPI_ENDPOINT_SERVERS = "micronaut.openapi.endpoint.servers";
    /**
     * The name of the entry for Endpoint security requirements in the context.
     */
    public static final String MICRONAUT_OPENAPI_ENDPOINT_SECURITY_REQUIREMENTS = "micronaut.openapi.endpoint.security.requirements";
    /**
     * Is this property true, output file format will be JSON, otherwise YAML.
     */
    public static final String MICRONAUT_OPENAPI_JSON_FORMAT = "micronaut.openapi.json.format";
    /**
     * The name of the result swagger file.
     * <p>
     * Default filename is &lt;info.title&gt;-&lt;info.version&gt;.yml.
     * If info annotation not set, filename will be swagger.yml.
     */
    public static final String MICRONAUT_OPENAPI_FILENAME = "micronaut.openapi.filename";
    /**
     * Active micronaut environments which will be used for @Requires annotations.
     */
    public static final String MICRONAUT_OPENAPI_ENVIRONMENTS = "micronaut.openapi.environments";
    /**
     * Is this property true, properties wll be loaded in the standard way from application.yml.
     * Also, environments from "micronaut.openapi.environments" property will set as additional environments,
     * if you want to set specific environment name for openAPI generator.
     * <br>
     * Default value is "true".
     */
    public static final String MICRONAUT_ENVIRONMENT_ENABLED = "micronaut.environment.enabled";
    /**
     * Is this property true, micronaut-openapi will process micronaut-security properties and annotations
     * to construct openapi security schema.
     * <br>
     * Default value is "true".
     */
    public static final String MICRONAUT_OPENAPI_SECURITY_ENABLED = "micronaut.openapi.security.enabled";
    /**
     * Is this property true, micronaut-openapi will process micronaut-router versioning prpoerties and annotations.
     * <br>
     * Default value is "true".
     */
    public static final String MICRONAUT_OPENAPI_VERSIONING_ENABLED = "micronaut.openapi.versioning.enabled";
    /**
     * Config file locations. By default, micronaut-openapi search config in standard path:
     * &lt;project_path&gt;/src/main/resources/
     * <p>
     * You can set your custom paths separated by ','. To set absolute paths use prefix 'file:',
     * classpath paths use prefix 'classpath:' or use prefix 'project:' to set paths from project
     * directory.
     */
    public static final String MICRONAUT_CONFIG_FILE_LOCATIONS = "micronaut.openapi.config.file.locations";

    /**
     * Loaded micronaut-http server context path property.
     */
    public static final String MICRONAUT_SERVER_CONTEXT_PATH = "micronaut.server.context-path";
    /**
     * Final calculated openapi filenames.
     */
    public static final String MICRONAUT_INTERNAL_OPENAPI_FILENAMES = "micronaut.internal.openapi.filenames";
    /**
     * Loaded micronaut-http-server-netty property (json-view.enabled).
     */
    public static final String MICRONAUT_JACKSON_JSON_VIEW_ENABLED = "jackson.json-view.enabled";
    /**
     * Loaded micronaut environment.
     */
    private static final String MICRONAUT_ENVIRONMENT = "micronaut.environment";
    /**
     * Loaded into context jackson.json-view.enabled property value.
     */
    private static final String MICRONAUT_INTERNAL_JACKSON_JSON_VIEW_ENABLED = "micronaut.internal.jackson.json-view.enabled";
    private static final String MICRONAUT_ENVIRONMENT_CREATED = "micronaut.environment.created";
    private static final String MICRONAUT_OPENAPI_PROPERTIES = "micronaut.openapi.properties";
    private static final String MICRONAUT_OPENAPI_ENDPOINTS = "micronaut.openapi.endpoints";
    /**
     * Properties prefix to set custom schema implementations for selected clases.
     * For example, if you want to set simple 'java.lang.String' class to some complex 'org.somepackage.MyComplexType' class you need to write:
     * <p>
     * micronaut.openapi.schema.org.somepackage.MyComplexType=java.lang.String
     * <p>
     * Also, you can set it in your application.yml file like this:
     * <p>
     * micronaut:
     *   openapi:
     *     schema:
     *       org.somepackage.MyComplexType: java.lang.String
     *       org.somepackage.MyComplexType2: java.lang.Integer
     *       ...
     */
    private static final String MICRONAUT_OPENAPI_SCHEMA = "micronaut.openapi.schema";
    private static final String MICRONAUT_CUSTOM_SCHEMAS = "micronaut.internal.custom.schemas";
    /**
     * Properties prefix to set schema name prefix or postfix by package.
     * For example, if you have some classes with same names in different packages you can set postfix like this:
     * <p>
     * micronaut.openapi.schema-postfix.org.api.v1_0_0=1_0_0
     * micronaut.openapi.schema-postfix.org.api.v2_0_0=2_0_0
     * <p>
     * Also, you can set it in your application.yml file like this:
     * <p>
     * micronaut:
     *   openapi:
     *     schema-postfix:
     *         org.api.v1_0_0: 1_0_0
     *         org.api.v2_0_0: 2_0_0
     *       ...
     */
    private static final String MICRONAUT_OPENAPI_SCHEMA_PREFIX = "micronaut.openapi.schema-prefix";
    private static final String MICRONAUT_OPENAPI_SCHEMA_POSTFIX = "micronaut.openapi.schema-postfix";
    private static final String MICRONAUT_SCHEMA_DECORATORS = "micronaut.internal.schema-decorators";
    /**
     * Properties prefix to set custom schema implementations for selected clases.
     * For example, if you want to set simple 'java.lang.String' class to some complex 'org.somepackage.MyComplexType' class you need to write:
     * <p>
     * -Dmicronaut.openapi.group.my-group1.title="Title 1"
     * <p>
     * Also, you can set it in your application.yml file like this:
     * <p>
     * micronaut:
     *   openapi:
     *     group:
     *       my-group1:
     *         title: Title 1
     *         filename: swagger-${group}-${apiVersion}-${version}.yml
     *       my-group2:
     *         title: Title 2
     *       ...
     */
    private static final String MICRONAUT_OPENAPI_GROUPS = "micronaut.openapi.groups";
    private static final String MICRONAUT_INTERNAL_GROUPS = "micronaut.internal.groups";
    /**
     * Loaded expandable properties. Need to save them to reuse in diffferent places.
     */
    private static final String MICRONAUT_INTERNAL_EXPANDBLE_PROPERTIES = "micronaut.internal.expandable.props";
    /**
     * Flag that shows that the expandable properties are already loaded into the context.
     */
    private static final String MICRONAUT_INTERNAL_EXPANDBLE_PROPERTIES_LOADED = "micronaut.internal.expandable.props.loaded";
    /**
     * Loaded micronaut-security and microanut-openanpi security properties.
     */
    private static final String MICRONAUT_INTERNAL_SECURITY_PROPERTIES = "micronaut.internal.security.properties";
    /**
     * Loaded micronaut-router and microanut-openanpi router versioning properties.
     */
    private static final String MICRONAUT_INTERNAL_ROUTER_VERSIONING_PROPERTIES = "micronaut.internal.router.versioning.properties";
    /**
     * Loaded micronaut.openapi.enabled property value.
     * <br>
     * Default: true
     */
    private static final String MICRONAUT_INTERNAL_OPENAPI_ENABLED = "micronaut.internal.openapi.enabled";
    /**
     * Default autogenerated security schema name.
     */
    private static final String DEFAULT_SECURITY_SCHEMA_NAME = "Authorization";

    private static final Argument<List<Map.Entry<String, String>>> EXPANDABLE_PROPERTIES_ARGUMENT = new GenericArgument<>() { };
    private static final String EXT_YML = ".yml";
    private static final String EXT_YAML = ".yaml";
    private static final String EXT_JSON = ".json";

    private ClassElement classElement;
    private int visitedElements = -1;

    public static boolean isOpenApiEnabled(VisitorContext context) {
        Boolean loadedValue = context.get(MICRONAUT_INTERNAL_OPENAPI_ENABLED, Boolean.class).orElse(null);
        if (loadedValue == null) {
            boolean value = OpenApiApplicationVisitor.getBooleanProperty(MICRONAUT_OPENAPI_ENABLED, true, context);
            context.put(MICRONAUT_INTERNAL_OPENAPI_ENABLED, value);
            System.setProperty(MICRONAUT_OPENAPI_ENABLED, Boolean.toString(value));
            return value;
        }
        return loadedValue;
    }

    @Override
    public void visitClass(ClassElement element, VisitorContext context) {
        try {
            incrementVisitedElements(context);
            if (!isOpenApiEnabled(context)) {
                return;
            }
            context.info("Generating OpenAPI Documentation");
            OpenAPI openApi = readOpenApi(element, context);

            // Handle Application securityRequirements schemes
            processSecuritySchemes(element, context);

            mergeAdditionalSwaggerFiles(element, context, openApi);
            // handle type level tags
            List<io.swagger.v3.oas.models.tags.Tag> tagList = processOpenApiAnnotation(
                element,
                context,
                Tag.class,
                io.swagger.v3.oas.models.tags.Tag.class,
                openApi.getTags()
            );
            openApi.setTags(tagList);

            // handle type level security requirements
            List<io.swagger.v3.oas.models.security.SecurityRequirement> securityRequirements = readSecurityRequirements(element);
            if (openApi.getSecurity() != null) {
                securityRequirements.addAll(openApi.getSecurity());
            }

            openApi.setSecurity(securityRequirements);

            // handle type level servers
            List<io.swagger.v3.oas.models.servers.Server> servers = processOpenApiAnnotation(
                element,
                context,
                Server.class,
                io.swagger.v3.oas.models.servers.Server.class,
                openApi.getServers()
            );
            openApi.setServers(servers);

        Optional<OpenAPI> attr = context.get(Utils.ATTR_OPENAPI, OpenAPI.class);
        if (attr.isPresent()) {
            OpenAPI existing = attr.get();
            Optional.ofNullable(openApi.getInfo())
                    .ifPresent(existing::setInfo);
                copyOpenApi(existing, openApi);
            } else {
                context.put(Utils.ATTR_OPENAPI, openApi);
            }

            if (Utils.isTestMode()) {
                Utils.resolveOpenApi(context);
            }

            classElement = element;
        } catch (Throwable t) {
            context.warn("Error with processing class:\n" + Utils.printStackTrace(t), classElement);
        }
    }

    public static SchemaDecorator getSchemaDecoration(String packageName, VisitorContext context) {

        Map<String, SchemaDecorator> schemaDecorators = (Map<String, SchemaDecorator>) context.get(MICRONAUT_SCHEMA_DECORATORS, Map.class).orElse(null);
        if (schemaDecorators != null) {
            return schemaDecorators.get(packageName);
        }

        schemaDecorators = new HashMap<>();

        // first read system properties
        Properties sysProps = System.getProperties();
        readSchemaDecorators(sysProps, schemaDecorators, context);

        // second read openapi.properties file
        Properties fileProps = readOpenApiConfigFile(context);
        readSchemaDecorators(fileProps, schemaDecorators, context);

        // third read environments properties
        Environment environment = getEnv(context);
        if (environment != null) {
            for (Map.Entry<String, Object> entry : environment.getProperties(MICRONAUT_OPENAPI_SCHEMA_PREFIX, StringConvention.RAW).entrySet()) {
                SchemaDecorator decorator = schemaDecorators.get(entry.getKey());
                if (decorator == null) {
                    decorator = new SchemaDecorator();
                    schemaDecorators.put(entry.getKey(), decorator);
                }
                decorator.setPrefix((String) entry.getValue());
            }

            for (Map.Entry<String, Object> entry : environment.getProperties(MICRONAUT_OPENAPI_SCHEMA_POSTFIX, StringConvention.RAW).entrySet()) {
                SchemaDecorator decorator = schemaDecorators.get(entry.getKey());
                if (decorator == null) {
                    decorator = new SchemaDecorator();
                    schemaDecorators.put(entry.getKey(), decorator);
                }
                decorator.setPostfix((String) entry.getValue());
            }
        }

        context.put(MICRONAUT_SCHEMA_DECORATORS, schemaDecorators);

        return schemaDecorators.get(packageName);
    }

    private static void readSchemaDecorators(Properties props, Map<String, SchemaDecorator> schemaDecorators, VisitorContext context) {

        for (String prop : props.stringPropertyNames()) {
            boolean isPrefix = false;
            String packageName = null;
            if (prop.startsWith(MICRONAUT_OPENAPI_SCHEMA_PREFIX)) {
                packageName = prop.substring(MICRONAUT_OPENAPI_SCHEMA_PREFIX.length() + 1);
                isPrefix = true;
            } else if (prop.startsWith(MICRONAUT_OPENAPI_SCHEMA_POSTFIX)) {
                packageName = prop.substring(MICRONAUT_OPENAPI_SCHEMA_POSTFIX.length() + 1);
            }
            if (StringUtils.isEmpty(packageName)) {
                continue;
            }
            SchemaDecorator schemaDecorator = schemaDecorators.get(packageName);
            if (schemaDecorator == null) {
                schemaDecorator = new SchemaDecorator();
                schemaDecorators.put(packageName, schemaDecorator);
            }
            if (isPrefix) {
                schemaDecorator.setPrefix(props.getProperty(prop));
            } else {
                schemaDecorator.setPostfix(props.getProperty(prop));
            }
        }
    }

    public static ClassElement getCustomSchema(String className, Map<String, ClassElement> typeArgs, VisitorContext context) {

        Map<String, CustomSchema> customSchemas = (Map<String, CustomSchema>) context.get(MICRONAUT_CUSTOM_SCHEMAS, Map.class).orElse(null);
        if (customSchemas != null) {
            String key = getClassNameWithGenerics(className, typeArgs);

            CustomSchema customSchema = customSchemas.get(key);
            if (customSchema != null) {
                return customSchema.classElement;
            }
            customSchema = customSchemas.get(className);

            return customSchema != null ? customSchema.classElement : null;
        }

        customSchemas = new HashMap<>();

        // first read system properties
        Properties sysProps = System.getProperties();
        readCustomSchemas(sysProps, customSchemas, context);

        // second read openapi.properties file
        Properties fileProps = readOpenApiConfigFile(context);
        readCustomSchemas(fileProps, customSchemas, context);

        // third read environments properties
        Environment environment = getEnv(context);
        if (environment != null) {
            for (Map.Entry<String, Object> entry : environment.getProperties(MICRONAUT_OPENAPI_SCHEMA, StringConvention.RAW).entrySet()) {
                String configuredClassName = entry.getKey();
                String targetClassName = (String) entry.getValue();
                readCustomSchema(configuredClassName, targetClassName, customSchemas, context);
            }
        }

        context.put(MICRONAUT_CUSTOM_SCHEMAS, customSchemas);

        if (customSchemas.isEmpty()) {
            return null;
        }

        String key = getClassNameWithGenerics(className, typeArgs);

        CustomSchema customSchema = customSchemas.get(key);
        if (customSchema != null) {
            return customSchema.classElement;
        }
        customSchema = customSchemas.get(className);

        return customSchema != null ? customSchema.classElement : null;
    }

    private static String getClassNameWithGenerics(String className, Map<String, ClassElement> typeArgs) {
        StringBuilder key = new StringBuilder(className);
        if (!typeArgs.isEmpty()) {
            key.append('<');
            boolean isFirst = true;
            for (ClassElement typeArg : typeArgs.values()) {
                if (!isFirst) {
                    key.append(',');
                }
                key.append(typeArg.getName());
                isFirst = false;
            }
            key.append('>');
        }
        return key.toString();
    }

    private static void readCustomSchemas(Properties props, Map<String, CustomSchema> customSchemas, VisitorContext context) {

        for (String prop : props.stringPropertyNames()) {
            if (!prop.startsWith(MICRONAUT_OPENAPI_SCHEMA) || prop.startsWith(MICRONAUT_OPENAPI_SCHEMA_PREFIX) || prop.startsWith(MICRONAUT_OPENAPI_SCHEMA_POSTFIX)) {
                continue;
            }
            String className = prop.substring(MICRONAUT_OPENAPI_SCHEMA.length() + 1);
            String targetClassName = props.getProperty(prop);
            readCustomSchema(className, targetClassName, customSchemas, context);
        }
    }

    private static void readCustomSchema(String className, String targetClassName, Map<String, CustomSchema> customSchemas, VisitorContext context) {
        if (customSchemas.containsKey(className)) {
            return;
        }
        ClassElement targetClassElement = context.getClassElement(targetClassName).orElse(null);
        if (targetClassElement == null) {
            context.warn("Can't find class " + targetClassName + " in classpath. Skip it.", null);
            return;
        }

        List<String> configuredTypeArgs = null;
        int genericNameStart = className.indexOf('<');
        if (genericNameStart > 0) {
            String[] generics = className.substring(genericNameStart + 1, className.indexOf('>')).split(",");
            configuredTypeArgs = new ArrayList<>();
            for (String generic : generics) {
                configuredTypeArgs.add(generic.trim());
            }
        }

        customSchemas.put(className, new CustomSchema(configuredTypeArgs, targetClassElement));
    }

    public static String getConfigurationProperty(String key, VisitorContext context) {
        String value = System.getProperty(key);
        if (value == null) {
            value = readOpenApiConfigFile(context).getProperty(key);
        }
        if (value != null) {
            return value;
        }
        Environment environment = getEnv(context);
        return environment != null ? environment.get(key, String.class).orElse(null) : null;
    }

    public static boolean isJsonViewEnabled(VisitorContext context) {

        Boolean isJsonViewEnabled = context.get(MICRONAUT_INTERNAL_JACKSON_JSON_VIEW_ENABLED, Boolean.class).orElse(null);
        if (isJsonViewEnabled != null) {
            return isJsonViewEnabled;
        }

        isJsonViewEnabled = getBooleanProperty(MICRONAUT_JACKSON_JSON_VIEW_ENABLED, false, context);
        context.put(MICRONAUT_INTERNAL_JACKSON_JSON_VIEW_ENABLED, isJsonViewEnabled);

        return isJsonViewEnabled;
    }

    public static SecurityProperties getSecurityProperties(VisitorContext context) {

        SecurityProperties securityProperties = context.get(MICRONAUT_INTERNAL_SECURITY_PROPERTIES, SecurityProperties.class).orElse(null);
        if (securityProperties != null) {
            return securityProperties;
        }

        // load micronaut security properies
        Environment environment = getEnv(context);
        List<InterceptUrlMapPattern> interceptUrlMapPatterns;
        if (environment != null) {
            interceptUrlMapPatterns = environment.get("micronaut.security.intercept-url-map", Argument.listOf(InterceptUrlMapPattern.class)).orElse(Collections.emptyList());
        } else {
            interceptUrlMapPatterns = Collections.emptyList();
        }

        String defaultSchemaName = getConfigurationProperty(MICRONAUT_OPENAPI_SECURITY_DEFAULT_SCHEMA_NAME, context);
        if (StringUtils.isEmpty(defaultSchemaName)) {
            defaultSchemaName = DEFAULT_SECURITY_SCHEMA_NAME;
        }

        boolean tokenEnabled = getBooleanProperty("micronaut.security.token.enabled", false, context);

        securityProperties = new SecurityProperties(
            getBooleanProperty(MICRONAUT_OPENAPI_SECURITY_ENABLED, true, context),
            getBooleanProperty("micronaut.security.enabled", false, context),
            defaultSchemaName,
            interceptUrlMapPatterns,
            tokenEnabled,
            getBooleanProperty("micronaut.security.token.jwt.enabled", tokenEnabled, context),
            getBooleanProperty("micronaut.security.token.jwt.bearer", tokenEnabled, context),
            getBooleanProperty("micronaut.security.token.jwt.cookie.enabled", false, context),
            getBooleanProperty("micronaut.security.oauth2.enabled", false, context),
            getBooleanProperty("micronaut.security.basic-auth.enabled", false, context)
        );

        context.put(MICRONAUT_INTERNAL_SECURITY_PROPERTIES, securityProperties);

        return securityProperties;
    }

    public static RouterVersioningProperties getRouterVersioningProperties(VisitorContext context) {

        RouterVersioningProperties routerVersioningProperties = context.get(MICRONAUT_INTERNAL_ROUTER_VERSIONING_PROPERTIES, RouterVersioningProperties.class).orElse(null);
        if (routerVersioningProperties != null) {
            return routerVersioningProperties;
        }

        routerVersioningProperties = new RouterVersioningProperties(
            getBooleanProperty(MICRONAUT_OPENAPI_VERSIONING_ENABLED, true, context),
            getBooleanProperty("micronaut.router.versioning.enabled", false, context),
            getBooleanProperty("micronaut.router.versioning.header.enabled", false, context),
            getListStringsProperty("micronaut.router.versioning.header.names", Collections.singletonList(DEFAULT_HEADER_NAME), context),
            getBooleanProperty("micronaut.router.versioning.parameter.enabled", false, context),
            getListStringsProperty("micronaut.router.versioning.parameter.names", Collections.singletonList(DEFAULT_PARAMETER_NAME), context)
        );

        context.put(MICRONAUT_INTERNAL_ROUTER_VERSIONING_PROPERTIES, routerVersioningProperties);

        return routerVersioningProperties;
    }

    public static GroupProperties getGroupProperties(String groupName, VisitorContext context) {
        if (groupName == null) {
            return null;
        }
        Map<String, GroupProperties> allGroupsProperties = getGroupsPropertiesMap(context);
        return CollectionUtils.isNotEmpty(allGroupsProperties) ? allGroupsProperties.get(groupName) : null;
    }

    public static Map<String, GroupProperties> getGroupsPropertiesMap(VisitorContext context) {

        Map<String, GroupProperties> groupPropertiesMap = (Map<String, GroupProperties>) context.get(MICRONAUT_INTERNAL_GROUPS, Map.class).orElse(null);
        if (groupPropertiesMap != null) {
            return groupPropertiesMap;
        }

        groupPropertiesMap = new HashMap<>();

        // first read system properties
        Properties sysProps = System.getProperties();
        readGroupsProperties(sysProps, groupPropertiesMap, context);

        // second read openapi.properties file
        Properties fileProps = readOpenApiConfigFile(context);
        readGroupsProperties(fileProps, groupPropertiesMap, context);

        // third read environments properties
        Environment environment = getEnv(context);
        if (environment != null) {
            for (Map.Entry<String, Object> entry : environment.getProperties(MICRONAUT_OPENAPI_GROUPS, StringConvention.RAW).entrySet()) {
                String entryKey = entry.getKey();
                String[] propParts = entryKey.split("\\.");
                String propName = propParts[propParts.length - 1];
                String groupName = entryKey.substring(0, entryKey.length() - propName.length() - 1);
                setGroupProperty(groupName, propName, entry.getValue(), groupPropertiesMap, context);
            }
        }

        Utils.getAllKnownGroups().addAll(groupPropertiesMap.keySet());

        context.put(MICRONAUT_INTERNAL_GROUPS, groupPropertiesMap);

        return groupPropertiesMap;
    }

    private static void readGroupsProperties(Properties props, Map<String, GroupProperties> groupPropertiesMap, VisitorContext context) {

        for (String prop : props.stringPropertyNames()) {
            if (!prop.startsWith(MICRONAUT_OPENAPI_GROUPS)) {
                continue;
            }
            int groupNameIndexEnd = prop.indexOf('.', MICRONAUT_OPENAPI_GROUPS.length() + 1);
            if (groupNameIndexEnd < 0) {
                continue;
            }
            String groupName = prop.substring(MICRONAUT_OPENAPI_GROUPS.length() + 1, groupNameIndexEnd);
            String propertyName = prop.substring(groupNameIndexEnd + 1);
            String value = props.getProperty(prop);
            setGroupProperty(groupName, prop, value, groupPropertiesMap, context);
        }
    }

    private static void setGroupProperty(String groupName, String propertyName, Object value, Map<String, GroupProperties> groupPropertiesMap, VisitorContext context) {
        if (value == null) {
            return;
        }
        String valueStr = value.toString();
        GroupProperties groupProperties = groupPropertiesMap.computeIfAbsent(groupName, GroupProperties::new);
        switch (propertyName.toLowerCase()) {
            case "displayname", "display-name" -> {
                if (groupProperties.getDisplayName() == null) {
                    groupProperties.setDisplayName(valueStr);
                }
            }
            case "filename", "file-name" -> {
                if (groupProperties.getFilename() == null) {
                    groupProperties.setFilename(valueStr);
                }
            }
            case "packages" -> {
                if (groupProperties.getPackages() == null) {
                    List<PackageProperties> packages = new ArrayList<>();
                    for (String groupPackage : valueStr.split(",")) {
                        packages.add(getPackageProperties(groupPackage));
                    }
                    groupProperties.setPackages(packages);
                }
            }
            case "primary" -> {
                if (groupProperties.getPrimary() == null) {
                    groupProperties.setPrimary(Boolean.valueOf(valueStr));
                }
            }
            case "commonexclude", "common-exclude" -> {
                if (groupProperties.getCommonExclude() == null) {
                    groupProperties.setCommonExclude(Boolean.valueOf(valueStr));
                }
            }
            case "packagesexclude", "packages-exclude" -> {
                if (groupProperties.getPackagesExclude() == null) {
                    List<PackageProperties> packagesExclude = new ArrayList<>();
                    for (String groupPackage : valueStr.split(",")) {
                        packagesExclude.add(getPackageProperties(groupPackage));
                    }
                    groupProperties.setPackagesExclude(packagesExclude);
                }
            }
            default -> {
            }
        }
    }

    private static PackageProperties getPackageProperties(String groupPackage) {
        groupPackage = groupPackage.trim();
        boolean includeSubpackages = groupPackage.endsWith("*");
        if (includeSubpackages) {
            groupPackage = groupPackage.substring(0, groupPackage.length() - 2);
        }
        if (groupPackage.endsWith(".")) {
            groupPackage = groupPackage.substring(0, groupPackage.length() - 2);
        }
        return new PackageProperties(groupPackage, includeSubpackages);
    }

    public static boolean getBooleanProperty(String property, boolean defaultValue, VisitorContext context) {
        String str = getConfigurationProperty(property, context);
        if (StringUtils.isEmpty(str)) {
            return defaultValue;
        }
        return !StringUtils.FALSE.equalsIgnoreCase(str);
    }

    public static List<String> getListStringsProperty(String property, List<String> defaultValue, VisitorContext context) {
        String strValue = System.getProperty(property);
        if (StringUtils.isEmpty(strValue)) {
            strValue = readOpenApiConfigFile(context).getProperty(property);
        }
        if (StringUtils.isNotEmpty(strValue)) {
            List<String> result = new ArrayList<>();
            for (String item : strValue.split(",")) {
                result.add(item.trim());
            }
            return result;
        }

        Environment env = getEnv(context);
        if (env != null) {
            return env.get(property, Argument.LIST_OF_STRING).orElse(defaultValue);
        }
        return defaultValue;
    }

    @Nullable
    public static Environment getEnv(VisitorContext context) {
        if (!isEnvEnabled(context)) {
            return null;
        }

        Environment existedEnvironment = context != null ? context.get(MICRONAUT_ENVIRONMENT, Environment.class).orElse(null) : null;
        Boolean envCreated = context != null ? context.get(MICRONAUT_ENVIRONMENT_CREATED, Boolean.class).orElse(null) : null;
        if (envCreated != null && envCreated) {
            return existedEnvironment;
        }

        Environment environment = createEnv(context);
        if (context != null) {
            context.put(MICRONAUT_ENVIRONMENT, environment);
            context.put(MICRONAUT_ENVIRONMENT_CREATED, true);
        }

        return environment;
    }

    private static boolean isEnvEnabled(VisitorContext context) {
        String isEnabledStr = System.getProperty(MICRONAUT_ENVIRONMENT_ENABLED, readOpenApiConfigFile(context).getProperty(MICRONAUT_ENVIRONMENT_ENABLED));
        boolean isEnabled = true;
        if (StringUtils.isNotEmpty(isEnabledStr)) {
            isEnabled = Boolean.parseBoolean(isEnabledStr);
        }
        if (context != null) {
            context.put(MICRONAUT_ENVIRONMENT_ENABLED, isEnabled);
        }
        return isEnabled;
    }

    public static List<String> getActiveEnvs(VisitorContext context) {

        if (!isEnvEnabled(context)) {
            return Collections.emptyList();
        }

        String activeEnvStr = System.getProperty(MICRONAUT_OPENAPI_ENVIRONMENTS, readOpenApiConfigFile(context).getProperty(MICRONAUT_OPENAPI_ENVIRONMENTS));
        List<String> activeEnvs;
        if (StringUtils.isNotEmpty(activeEnvStr)) {
            activeEnvs = Stream.of(activeEnvStr)
                .filter(StringUtils::isNotEmpty)
                .flatMap(s -> Arrays.stream(s.split(",")))
                .map(String::trim)
                .collect(Collectors.toList());
        } else {
            activeEnvs = new ArrayList<>();
        }
        return activeEnvs;
    }

    private static Environment createEnv(VisitorContext context) {

        ApplicationContextConfiguration configuration = new ApplicationContextConfiguration() {
            @Override
            public Optional<MutableConversionService> getConversionService() {
                MutableConversionService conversionService = new DefaultMutableConversionService();
                conversionService.addConverter(Map.class, InterceptUrlMapPattern.class, new InterceptUrlMapConverter(conversionService));
                return Optional.of(conversionService);
            }

            @Override
            public ClassPathResourceLoader getResourceLoader() {
                ClassLoader classLoader = ApplicationContextConfiguration.class.getClassLoader();
                if (classLoader == null) {
                    classLoader = Thread.currentThread().getContextClassLoader();
                }
                if (classLoader == null) {
                    classLoader = ClassPathResourceLoader.class.getClassLoader();
                }
                if (classLoader == null) {
                    classLoader = ClassLoader.getSystemClassLoader();
                }
                return new DefaultClassPathResourceLoader(classLoader, null, false, false);
            }

            @Override
            @NonNull
            public List<String> getEnvironments() {
                return getActiveEnvs(context);
            }
        };

        Environment environment = null;
        try {
            environment = new AnnProcessorEnvironment(configuration, context);
            environment.start();
            return environment;
        } catch (Exception e) {
            if (context != null) {
                context.warn("Can't create environment: " + e.getMessage() + ".\n" + Utils.printStackTrace(e), null);
            }
        }
        return environment;
    }

    private ClassLoader resolveClassLoader() {
        final ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        if (contextClassLoader != null) {
            return contextClassLoader;
        }
        return DefaultApplicationContextBuilder.class.getClassLoader();
    }

    /**
     * Merge the OpenAPI YAML and JSON files into one single file.
     *
     * @param element The element
     * @param context The visitor context
     * @param openAPI The {@link OpenAPI} object for the application
     */
    private void mergeAdditionalSwaggerFiles(ClassElement element, VisitorContext context, OpenAPI openAPI) {
        String additionalSwaggerFiles = getConfigurationProperty(MICRONAUT_OPENAPI_ADDITIONAL_FILES, context);
        if (StringUtils.isNotEmpty(additionalSwaggerFiles)) {
            Path directory = resolve(context, Paths.get(additionalSwaggerFiles));
            if (Files.isDirectory(directory)) {
                context.info("Merging Swagger OpenAPI YAML and JSON files from location: " + directory);
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, path -> isYaml(path.toString().toLowerCase()) || path.toString().toLowerCase().endsWith(EXT_JSON))) {
                    stream.forEach(path -> {
                        boolean isYaml = isYaml(path.toString().toLowerCase());
                        context.info("Reading Swagger OpenAPI " + (isYaml ? "YAML" : "JSON") + " file " + path.getFileName());
                        OpenAPI parsedOpenApi = null;
                        try {
                            parsedOpenApi = (isYaml ? ConvertUtils.getYamlMapper() : ConvertUtils.getJsonMapper()).readValue(path.toFile(), OpenAPI.class);
                        } catch (IOException e) {
                            context.warn("Unable to read file " + path.getFileName() + ": " + e.getMessage(), element);
                        }
                        copyOpenApi(openAPI, parsedOpenApi);
                    });
                } catch (IOException e) {
                    context.warn("Unable to read  file from " + directory + ": " + e.getMessage(), element);
                }
            } else {
                context.warn(directory + " does not exist or is not a directory", element);
            }
        }
    }

    private boolean isYaml(String path) {
        return path.endsWith(EXT_YML) || path.endsWith(EXT_YAML);
    }

    public static Path resolve(VisitorContext context, Path path) {
        if (!path.isAbsolute() && context != null) {
            Path projectPath = Utils.getProjectPath(context);
            if (projectPath != null) {
                path = projectPath.resolve(path);
            }
        }
        return path.toAbsolutePath();
    }

    /**
     * Returns the EndpointsConfiguration.
     *
     * @param context The context.
     *
     * @return The EndpointsConfiguration.
     */
    static EndpointsConfiguration endpointsConfiguration(VisitorContext context) {
        Optional<EndpointsConfiguration> cfg = context.get(MICRONAUT_OPENAPI_ENDPOINTS, EndpointsConfiguration.class);
        if (cfg.isPresent()) {
            return cfg.get();
        }
        EndpointsConfiguration conf = new EndpointsConfiguration(context, readOpenApiConfigFile(context));
        context.put(MICRONAUT_OPENAPI_ENDPOINTS, conf);
        return conf;
    }

    public static Properties readOpenApiConfigFile(VisitorContext context) {
        Optional<Properties> props = context != null ? context.get(MICRONAUT_OPENAPI_PROPERTIES, Properties.class) : Optional.empty();
        if (props.isPresent()) {
            return props.get();
        }
        Properties openApiProperties = new Properties();
        String cfgFile = System.getProperty(MICRONAUT_OPENAPI_CONFIG_FILE, OPENAPI_CONFIG_FILE);
        if (StringUtils.isNotEmpty(cfgFile)) {
            Path cfg = resolve(context, Paths.get(cfgFile));
            if (Files.isReadable(cfg)) {
                try (Reader reader = Files.newBufferedReader(cfg)) {
                    openApiProperties.load(reader);
                } catch (IOException e) {
                    if (context != null) {
                        context.warn("Fail to read OpenAPI configuration file: " + e.getMessage(), null);
                    }
                }
            } else if (Files.exists(cfg)) {
                if (context != null) {
                    context.warn("Can not read configuration file: " + cfg, null);
                }
            }
        }
        if (context != null) {
            context.put(MICRONAUT_OPENAPI_PROPERTIES, openApiProperties);
        }
        return openApiProperties;
    }

    /**
     * Copy information from one {@link OpenAPI} object to another.
     *
     * @param to The {@link OpenAPI} object to copy to
     * @param from The {@link OpenAPI} object to copy from
     */
    private void copyOpenApi(OpenAPI to, OpenAPI from) {
        if (to != null && from != null) {
            Optional.ofNullable(from.getTags()).ifPresent(tags -> tags.forEach(to::addTagsItem));
            Optional.ofNullable(from.getServers()).ifPresent(servers -> servers.forEach(to::addServersItem));
            Optional.ofNullable(from.getSecurity()).ifPresent(securityRequirements -> securityRequirements.forEach(to::addSecurityItem));
            Optional.ofNullable(from.getPaths()).ifPresent(paths -> paths.forEach(to::path));
            Optional.ofNullable(from.getComponents()).ifPresent(components -> {
                Map<String, Schema> schemas = components.getSchemas();

                if (CollectionUtils.isNotEmpty(schemas)) {
                    schemas.forEach((k, v) -> {
                        if (v.getName() == null) {
                            v.setName(k);
                        }
                    });
                    schemas.forEach(to::schema);
                }
                Map<String, SecurityScheme> securitySchemes = components.getSecuritySchemes();
                if (securitySchemes != null && !securitySchemes.isEmpty()) {
                    securitySchemes.forEach(to::schemaRequirement);
                }
            });
            Optional.ofNullable(from.getExternalDocs()).ifPresent(to::externalDocs);
            Optional.ofNullable(from.getExtensions()).ifPresent(extensions -> extensions.forEach(to::addExtension));
        }
    }

    private OpenAPI readOpenApi(ClassElement element, VisitorContext context) {
        return element.findAnnotation(OpenAPIDefinition.class).flatMap(o -> {
                    Optional<OpenAPI> result = toValue(o.getValues(), context, OpenAPI.class, null);
                    result.ifPresent(openAPI -> {
                        List<io.swagger.v3.oas.models.security.SecurityRequirement> securityRequirements =
                                o.getAnnotations("security", SecurityRequirement.class)
                                .stream()
                                .map(ConvertUtils::mapToSecurityRequirement)
                                .collect(Collectors.toList());
                        openAPI.setSecurity(securityRequirements);
                    });
                    return result;
                }).orElse(new OpenAPI());
    }

    private void renderViews(String title, Map<Pair<String, String>, OpenApiInfo> openApiInfos, Path destinationDir, VisitorContext context) throws IOException {
        String viewSpecification = getConfigurationProperty(MICRONAUT_OPENAPI_VIEWS_SPEC, context);
        OpenApiViewConfig cfg = OpenApiViewConfig.fromSpecification(viewSpecification, openApiInfos, readOpenApiConfigFile(context), context);
        if (cfg.isEnabled()) {
            cfg.setTitle(title);
            cfg.setSpecFile(openApiInfos.values().iterator().next().getSpecFilePath());
            cfg.setServerContextPath(getConfigurationProperty(MICRONAUT_OPENAPI_CONTEXT_SERVER_PATH, context));
            cfg.render(destinationDir, context);
        }
    }

    private static PropertyNamingStrategies.NamingBase fromName(String name) {
        if (name == null) {
            return null;
        }
        return switch (name.toUpperCase(Locale.US)) {
            case "LOWER_CAMEL_CASE" -> new LowerCamelCasePropertyNamingStrategy();
            case "UPPER_CAMEL_CASE" -> (PropertyNamingStrategies.NamingBase) PropertyNamingStrategies.UPPER_CAMEL_CASE;
            case "SNAKE_CASE" -> (PropertyNamingStrategies.NamingBase) PropertyNamingStrategies.SNAKE_CASE;
            case "UPPER_SNAKE_CASE" -> (PropertyNamingStrategies.NamingBase) PropertyNamingStrategies.UPPER_SNAKE_CASE;
            case "LOWER_CASE" -> (PropertyNamingStrategies.NamingBase) PropertyNamingStrategies.LOWER_CASE;
            case "KEBAB_CASE" -> (PropertyNamingStrategies.NamingBase) PropertyNamingStrategies.KEBAB_CASE;
            case "LOWER_DOT_CASE" -> (PropertyNamingStrategies.NamingBase) PropertyNamingStrategies.LOWER_DOT_CASE;
            default -> null;
        };
    }

    private Optional<Path> getDefaultFilePath(String fileName, VisitorContext context) {
        // default location
        Optional<GeneratedFile> generatedFile = context.visitMetaInfFile("swagger/" + fileName, Element.EMPTY_ELEMENT_ARRAY);
        if (generatedFile.isPresent()) {
            URI uri = generatedFile.get().toURI();
            // happens in tests 'mem:///CLASS_OUTPUT/META-INF/swagger/swagger.yml'
            if (uri.getScheme() != null && !uri.getScheme().equals("mem")) {
                Path specPath = Paths.get(uri);
                createDirectories(specPath, context);
                return Optional.of(specPath);
            }
        }
        context.warn("Unable to get swagger/" + fileName + " file.", null);
        return Optional.empty();
    }

    private Optional<Path> openApiSpecFile(String fileName, VisitorContext visitorContext) {
        Optional<Path> path = userDefinedSpecFile(visitorContext);
        if (path.isPresent()) {
            return path;
        }
        return getDefaultFilePath(fileName, visitorContext);
    }

    private Optional<Path> userDefinedSpecFile(VisitorContext context) {
        String targetFile = getConfigurationProperty(MICRONAUT_OPENAPI_TARGET_FILE, context);
        if (StringUtils.isEmpty(targetFile)) {
            return Optional.empty();
        }
        Path specFile = resolve(context, Paths.get(targetFile));
        createDirectories(specFile, context);
        return Optional.of(specFile);
    }

    private Path getViewsDestDir(Path defaultSwaggerFilePath, VisitorContext context) {
        String destDir = getConfigurationProperty(MICRONAUT_OPENAPI_VIEWS_DEST_DIR, context);
        if (StringUtils.isNotEmpty(destDir)) {
            Path destPath = resolve(context, Paths.get(destDir));
            createDirectories(destPath, context);
            return destPath;
        }
        return defaultSwaggerFilePath.getParent().resolve("views");
    }

    private static void createDirectories(Path f, VisitorContext visitorContext) {
        if (f.getParent() != null) {
            try {
                Files.createDirectories(f.getParent());
            } catch (IOException e) {
                visitorContext.warn("Fail to create directories for" + f + ": " + e.getMessage(), null);
            }
        }
    }

    private void applyPropertyNamingStrategy(OpenAPI openAPI, VisitorContext visitorContext) {
        final String namingStrategyName = getConfigurationProperty(MICRONAUT_OPENAPI_PROPERTY_NAMING_STRATEGY, visitorContext);
        final PropertyNamingStrategies.NamingBase propertyNamingStrategy = fromName(namingStrategyName);
        if (propertyNamingStrategy != null) {
            visitorContext.info("Using " + namingStrategyName + " property naming strategy.");
            if (openAPI.getComponents() != null && CollectionUtils.isNotEmpty(openAPI.getComponents().getSchemas())) {
                openAPI.getComponents().getSchemas().values().forEach(model -> {
                    Map<String, Schema> properties = model.getProperties();
                    if (properties != null) {
                        Map<String, Schema> newProperties = properties.entrySet().stream()
                            .collect(Collectors.toMap(entry -> propertyNamingStrategy.translate(entry.getKey()),
                                Map.Entry::getValue, (prop1, prop2) -> prop1, LinkedHashMap::new));
                        model.getProperties().clear();
                        model.setProperties(newProperties);
                    }
                    List<String> required = model.getRequired();
                    if (required != null) {
                        List<String> updatedRequired = required.stream().map(propertyNamingStrategy::translate).toList();
                        required.clear();
                        required.addAll(updatedRequired);
                    }
                });
            }
        }
    }

    private void applyPropertyServerContextPath(OpenAPI openAPI, VisitorContext visitorContext) {
        final String serverContextPath = getConfigurationProperty(MICRONAUT_OPENAPI_CONTEXT_SERVER_PATH, visitorContext);
        if (serverContextPath == null || serverContextPath.isEmpty()) {
            return;
        }
        visitorContext.info("Applying server context path: " + serverContextPath + " to Paths.");
        io.swagger.v3.oas.models.Paths paths = openAPI.getPaths();
        if (paths == null || paths.isEmpty()) {
            return;
        }
        final io.swagger.v3.oas.models.Paths newPaths = new io.swagger.v3.oas.models.Paths();
        for (Map.Entry<String, PathItem> path : paths.entrySet()) {
            final String mapping = path.getKey();
            String newPath = mapping.startsWith(serverContextPath) ? mapping : StringUtils.prependUri(serverContextPath, mapping);
            if (!newPath.startsWith("/") && !newPath.startsWith("$")) {
                newPath = "/" + newPath;
            }
            newPaths.addPathItem(newPath, path.getValue());
        }
        openAPI.setPaths(newPaths);
    }

    public static JsonNode resolvePlaceholders(ArrayNode anode, UnaryOperator<String> propertyExpander) {
        for (int i = 0; i < anode.size(); ++i) {
            anode.set(i, resolvePlaceholders(anode.get(i), propertyExpander));
        }
        return anode;
    }

    public static JsonNode resolvePlaceholders(ObjectNode onode, UnaryOperator<String> propertyExpander) {
        if (onode.isEmpty()) {
            return onode;
        }
        final ObjectNode newNode = onode.objectNode();
        for (Iterator<Map.Entry<String, JsonNode>> i = onode.fields(); i.hasNext(); ) {
            final Map.Entry<String, JsonNode> entry = i.next();
            newNode.set(propertyExpander.apply(entry.getKey()), resolvePlaceholders(entry.getValue(), propertyExpander));
        }
        return newNode;
    }

    public static JsonNode resolvePlaceholders(JsonNode node, UnaryOperator<String> propertyExpander) {
        if (node.isTextual()) {
            final String text = node.textValue();
            if (text == null || text.isBlank()) {
                return node;
            }
            final String newText = propertyExpander.apply(text);
            return text.equals(newText) ? node : TextNode.valueOf(newText);
        } else if (node.isArray()) {
            return resolvePlaceholders((ArrayNode) node, propertyExpander);
        } else if (node.isObject()) {
            return resolvePlaceholders((ObjectNode) node, propertyExpander);
        } else {
            return node;
        }
    }

    public static String expandProperties(String s, List<Map.Entry<String, String>> properties, VisitorContext context) {
        if (StringUtils.isEmpty(s) || !s.contains(Utils.PLACEHOLDER_PREFIX)) {
            return s;
        }

        // form openapi file (expandable properties)
        if (CollectionUtils.isNotEmpty(properties)) {
            for (Map.Entry<String, String> entry : properties) {
                s = s.replace(entry.getKey(), entry.getValue());
            }
        }

        return replacePlaceholders(s, context);
    }

    public static String replacePlaceholders(String value, VisitorContext context) {
        if (StringUtils.isEmpty(value) || !value.contains(Utils.PLACEHOLDER_PREFIX)) {
            return value;
        }
        // system properties
        if (CollectionUtils.isNotEmpty(System.getProperties())) {
            for (Map.Entry<Object, Object> sysProp : System.getProperties().entrySet()) {
                value = value.replace(Utils.PLACEHOLDER_PREFIX + sysProp.getKey().toString() + Utils.PLACEHOLDER_POSTFIX, sysProp.getValue().toString());
            }
        }

        // form openapi file
        for (Map.Entry<Object, Object> fileProp : OpenApiApplicationVisitor.readOpenApiConfigFile(context).entrySet()) {
            value = value.replace(Utils.PLACEHOLDER_PREFIX + fileProp.getKey().toString() + Utils.PLACEHOLDER_POSTFIX, fileProp.getValue().toString());
        }

        // from environments
        Environment environment = OpenApiApplicationVisitor.getEnv(context);
        if (environment != null) {
            value = environment.getPlaceholderResolver().resolvePlaceholders(value).orElse(value);
        }

        return value;
    }

    public static List<Map.Entry<String, String>> getExpandableProperties(VisitorContext context) {

        List<Map.Entry<String, String>> expandableProperties = new ArrayList<>();
        Optional<Boolean> propertiesLoaded = context.get(MICRONAUT_INTERNAL_EXPANDBLE_PROPERTIES_LOADED, Boolean.class);
        if (!propertiesLoaded.orElse(false)) {

            // first, check system properties and environmets config files
            AnnProcessorEnvironment env = (AnnProcessorEnvironment) getEnv(context);
            Map<String, Object> propertiesFromEnv = null;
            if (env != null) {
                try {
                    propertiesFromEnv = env.getProperties("micronaut.openapi.expand", null);
                } catch (Exception e) {
                    context.warn("Error:\n" + Utils.printStackTrace(e), null);
                }
            }

            Map<String, String> expandedPropsMap = new HashMap<>();
            if (CollectionUtils.isNotEmpty(propertiesFromEnv)) {
                for (Map.Entry<String, Object> entry : propertiesFromEnv.entrySet()) {
                    expandedPropsMap.put(entry.getKey(), entry.getValue().toString());
                }
            }

            // next, read openapi.properties file
            Properties openapiProps = readOpenApiConfigFile(context);
            for (Map.Entry<Object, Object> entry : openapiProps.entrySet()) {
                String key = entry.getKey().toString();
                if (!key.startsWith(MICRONAUT_OPENAPI_EXPAND_PREFIX)) {
                    continue;
                }
                expandedPropsMap.put(key, entry.getValue().toString());
            }

            // next, read system properties
            if (CollectionUtils.isNotEmpty(System.getProperties())) {
                for (Map.Entry<Object, Object> entry : System.getProperties().entrySet()) {
                    String key = entry.getKey().toString();
                    if (!key.startsWith(MICRONAUT_OPENAPI_EXPAND_PREFIX)) {
                        continue;
                    }
                    expandedPropsMap.put(key, entry.getValue().toString());
                }
            }

            for (Map.Entry<String, String> entry : expandedPropsMap.entrySet()) {
                String key = entry.getKey();
                if (key.startsWith(MICRONAUT_OPENAPI_EXPAND_PREFIX)) {
                    key = key.substring(MICRONAUT_OPENAPI_EXPAND_PREFIX.length());
                }
                expandableProperties.add(new AbstractMap.SimpleImmutableEntry<>("${" + key + '}', entry.getValue()));
            }

            context.put(MICRONAUT_INTERNAL_EXPANDBLE_PROPERTIES, expandableProperties);
            context.put(MICRONAUT_INTERNAL_EXPANDBLE_PROPERTIES_LOADED, true);
        } else {
            expandableProperties = context.get(MICRONAUT_INTERNAL_EXPANDBLE_PROPERTIES, EXPANDABLE_PROPERTIES_ARGUMENT).orElse(null);
        }

        return expandableProperties;
    }

    private static OpenAPI resolvePropertyPlaceHolders(OpenAPI openAPI, VisitorContext visitorContext) {
        List<Map.Entry<String, String>> expandableProperties = getExpandableProperties(visitorContext);
        if (CollectionUtils.isNotEmpty(expandableProperties)) {
            visitorContext.info("Expanding properties: " + expandableProperties);
        }
        JsonNode root = resolvePlaceholders(ConvertUtils.getYamlMapper().convertValue(openAPI, ObjectNode.class), s -> expandProperties(s, expandableProperties, visitorContext));
        return ConvertUtils.getYamlMapper().convertValue(root, OpenAPI.class);
    }

    @Override
    public void finish(VisitorContext context) {
        try {
            if (!isOpenApiEnabled(context)) {
                return;
            }
            if (visitedElements == visitedElements(context)) {
                // nothing new visited, avoid rewriting the files.
                return;
            }
            Optional<OpenAPI> attr = context.get(Utils.ATTR_OPENAPI, OpenAPI.class);
            if (attr.isEmpty()) {
                return;
            }
            OpenAPI openApi = attr.get();
            processEndpoints(context);

            mergeMicronautEndpointInfos(openApi, context);
            Map<Pair<String, String>, OpenApiInfo> openApiInfos = divideOpenapiByGroupsAndVersions(openApi, context);
            if (Utils.isTestMode()) {
                Utils.setTestReferences(openApiInfos);
            }

            String isJson = getConfigurationProperty(MICRONAUT_OPENAPI_JSON_FORMAT, context);
            boolean isYaml = !(StringUtils.isNotEmpty(isJson) && isJson.equalsIgnoreCase(StringUtils.TRUE));
            String documentTitle = "OpenAPI";

            for (Map.Entry<Pair<String, String>, OpenApiInfo> entry : openApiInfos.entrySet()) {

                OpenApiInfo openApiInfo = entry.getValue();

                openApi = openApiInfo.getOpenApi();

                openApi = postProcessOpenApi(openApi, context);
                // need to set test reference to openApi after post-processing
                if (Utils.isTestMode()) {
                    Utils.setTestReference(openApi);
                }

                String ext = isYaml ? EXT_YML : EXT_JSON;
                String fileName = "swagger" + ext;

                Info info = openApi.getInfo();
                if (info != null) {
                    documentTitle = Optional.ofNullable(info.getTitle()).orElse(Environment.DEFAULT_NAME);
                    documentTitle = documentTitle.toLowerCase(Locale.US).replace(' ', '-');
                    String version = info.getVersion();
                    if (version != null) {
                        documentTitle = documentTitle + '-' + version;
                    }
                    fileName = documentTitle + ext;
                }

                String versionFromInfo = info != null && info.getVersion() != null ? info.getVersion() : StringUtils.EMPTY_STRING;

                String fileNameFromConfig = getConfigurationProperty(MICRONAUT_OPENAPI_FILENAME, context);
                if (StringUtils.isNotEmpty(fileNameFromConfig)) {
                    fileName = replacePlaceholders(fileNameFromConfig, context) + ext;
                    if (fileName.contains("${version}")) {
                        fileName = fileName.replaceAll("\\$\\{version}", versionFromInfo);
                    }
                }

                // contruct filename for group
                if (openApiInfos.size() > 1) {
                    if (StringUtils.isNotEmpty(openApiInfo.getFilename())) {
                        fileName = openApiInfo.getFilename();
                    } else {

                        // default name: swagger-<version>-<groupName>-<apiVersion>

                        fileName = fileName.substring(0, fileName.length() - ext.length())
                            + (openApiInfo.getGroupName() != null ? "-" + openApiInfo.getGroupName() : StringUtils.EMPTY_STRING)
                            + (openApiInfo.getVersion() != null ? "-" + openApiInfo.getVersion() : StringUtils.EMPTY_STRING);
                    }

                    fileName = replacePlaceholders(fileName, context) + ext;
                    if (fileName.contains("${apiVersion}")) {
                        fileName = fileName.replaceAll("\\$\\{apiVersion}", openApiInfo.getVersion() != null ? openApiInfo.getVersion() : versionFromInfo);
                    }
                    if (fileName.contains("${version}")) {
                        fileName = fileName.replaceAll("\\$\\{version}", versionFromInfo);
                    }
                    if (fileName.contains("${group}")) {
                        fileName = fileName.replaceAll("\\$\\{group}", openApiInfo.getGroupName() != null ? openApiInfo.getGroupName() : StringUtils.EMPTY_STRING);
                    }
                }
                if (fileName.contains("${")) {
                    context.warn("Can't set some placeholders in fileName: " + fileName, null);
                }

                openApiInfo.setFilename(fileName);
            }

            writeYamlToFile(openApiInfos, documentTitle, context, isYaml);

            visitedElements = visitedElements(context);
        } catch (Throwable t) {
            context.warn("Error:\n" + Utils.printStackTrace(t), null);
            throw t;
        }
    }

    private Map<Pair<String, String>, OpenApiInfo> divideOpenapiByGroupsAndVersions(OpenAPI openApi, VisitorContext context) {
        Map<String, List<EndpointInfo>> endpointInfosMap = Utils.getEndpointInfos();
        Set<String> allVersions = Utils.getAllKnownVersions();
        Set<String> allGroups = Utils.getAllKnownGroups();
        if (CollectionUtils.isEmpty(endpointInfosMap)
            || (CollectionUtils.isEmpty(allVersions) && CollectionUtils.isEmpty(allGroups))) {
            return Collections.singletonMap(Pair.NULL_STRING_PAIR, new OpenApiInfo(openApi));
        }

        List<EndpointInfo> commonEndpoints = new ArrayList<>();

        // key version, groupName
        Map<Pair<String, String>, OpenApiInfo> result = new HashMap<>();

        for (List<EndpointInfo> endpointInfos : endpointInfosMap.values()) {
            for (EndpointInfo endpointInfo : endpointInfos) {
                if (CollectionUtils.isEmpty(endpointInfo.getGroups()) && endpointInfo.getVersion() == null) {
                    commonEndpoints.add(endpointInfo);
                    continue;
                }
                for (String group : endpointInfo.getGroups()) {
                    if (CollectionUtils.isNotEmpty(endpointInfo.getExcludedGroups()) && endpointInfo.getExcludedGroups().contains(group)) {
                        continue;
                    }
                    OpenAPI newOpenApi = addOpenApiInfo(group, endpointInfo.getVersion(), openApi, result, context);
                    addOperation(endpointInfo, newOpenApi);
                }

                // if we have only versions without groups
                if (CollectionUtils.isEmpty(endpointInfo.getGroups())) {
                    OpenAPI newOpenApi = addOpenApiInfo(null, endpointInfo.getVersion(), openApi, result, context);
                    addOperation(endpointInfo, newOpenApi);
                }
            }
        }

        // add common endpoints (without group name)
        for (Map.Entry<Pair<String, String>, OpenApiInfo> entry : result.entrySet()) {

            String group = entry.getKey().getFirst();
            GroupProperties groupProperties = getGroupProperties(group, context);
            if (groupProperties != null && groupProperties.getCommonExclude() != null && groupProperties.getCommonExclude()) {
                continue;
            }

            OpenAPI openAPI = entry.getValue().getOpenApi();

            for (EndpointInfo commonEndpoint : commonEndpoints) {
                if (CollectionUtils.isNotEmpty(commonEndpoint.getExcludedGroups()) && commonEndpoint.getExcludedGroups().contains(group)) {
                    continue;
                }
                addOperation(commonEndpoint, openAPI);
            }
        }

        return result;
    }

    private void addOperation(EndpointInfo endpointInfo, OpenAPI openApi) {
        if (openApi == null) {
            return;
        }
        io.swagger.v3.oas.models.Paths paths = openApi.getPaths();
        if (paths == null) {
            paths = new io.swagger.v3.oas.models.Paths();
            openApi.setPaths(paths);
        }
        PathItem pathItem = paths.computeIfAbsent(endpointInfo.getUrl(), (pathurl) -> new PathItem());
        Operation operation = getOperationOnPathItem(pathItem, endpointInfo.getHttpMethod());
        if (operation == null) {
            setOperationOnPathItem(pathItem, endpointInfo.getHttpMethod(), endpointInfo.getOperation());
            return;
        }
        setOperationOnPathItem(pathItem, endpointInfo.getHttpMethod(), SchemaUtils.mergeOperations(operation, endpointInfo.getOperation()));
    }

    private OpenAPI addOpenApiInfo(String group, String version, OpenAPI openApi, Map<Pair<String, String>, OpenApiInfo> openApiInfoMap, VisitorContext context) {
        GroupProperties groupProperties = getGroupProperties(group, context);
        boolean hasGroupProperties = groupProperties != null;

        Pair<String, String> key = Pair.of(group, version);
        OpenApiInfo openApiInfo = openApiInfoMap.get(key);
        OpenAPI newOpenApi;
        if (openApiInfo == null) {

            Map<String, OpenAPI> knownOpenApis = Utils.getOpenApis();
            if (CollectionUtils.isNotEmpty(knownOpenApis) && knownOpenApis.containsKey(group)) {
                newOpenApi = knownOpenApis.get(group);
            } else {
                newOpenApi = new OpenAPI();
            }

            openApiInfo = new OpenApiInfo(
                version,
                group,
                hasGroupProperties ? groupProperties.getDisplayName() : null,
                hasGroupProperties ? groupProperties.getFilename() : null,
                newOpenApi
            );

            openApiInfoMap.put(key, openApiInfo);

            OpenAPI openApiCopy;
            try {
                openApiCopy = ConvertUtils.getJsonMapper().treeToValue(ConvertUtils.getJsonMapper().valueToTree(openApi), OpenAPI.class);
            } catch (JsonProcessingException e) {
                context.warn("Error\n" + Utils.printStackTrace(e), null);
                return null;
            }

            if (CollectionUtils.isEmpty(knownOpenApis) || !knownOpenApis.containsKey(group)) {
                newOpenApi.setTags(openApiCopy.getTags());
                newOpenApi.setServers(openApiCopy.getServers());
                newOpenApi.setInfo(openApiCopy.getInfo());
                newOpenApi.setSecurity(openApiCopy.getSecurity());
                newOpenApi.setExternalDocs(openApiCopy.getExternalDocs());
            }

            newOpenApi.setComponents(openApiCopy.getComponents());

        } else {
            newOpenApi = openApiInfo.getOpenApi();
        }

        return newOpenApi;
    }

    private void mergeMicronautEndpointInfos(OpenAPI openApi, VisitorContext context) {

        Map<String, List<EndpointInfo>> endpointInfosMap = Utils.getEndpointInfos();
        if (CollectionUtils.isEmpty(endpointInfosMap)) {
            return;
        }
        // we need to merge operations for single path without versions
        for (List<EndpointInfo> endpointInfos : endpointInfosMap.values()) {
            for (EndpointInfo endpointInfo : endpointInfos) {
                PathItem pathItem = openApi.getPaths().get(endpointInfo.getUrl());
                Operation operation = getOperationOnPathItem(pathItem, endpointInfo.getHttpMethod());
                if (operation == null) {
                    setOperationOnPathItem(pathItem, endpointInfo.getHttpMethod(), endpointInfo.getOperation());
                    continue;
                }
                if (endpointInfo.getVersion() == null) {
                    setOperationOnPathItem(pathItem, endpointInfo.getHttpMethod(), SchemaUtils.mergeOperations(operation, endpointInfo.getOperation()));
                }
            }
        }
    }

    @Override
    public int getOrder() {
        return 100;
    }

    private OpenAPI postProcessOpenApi(OpenAPI openApi, VisitorContext context) {

        applyPropertyNamingStrategy(openApi, context);
        applyPropertyServerContextPath(openApi, context);

        normalizeOpenApi(openApi);
        // Process after sorting so order is stable
        new JacksonDiscriminatorPostProcessor().addMissingDiscriminatorType(openApi);
        new OpenApiOperationsPostProcessor().processOperations(openApi);

        // remove unused schemas
        try {
            if (openApi.getComponents() != null) {
                Map<String, Schema> schemas = openApi.getComponents().getSchemas();
                if (CollectionUtils.isNotEmpty(schemas)) {
                    String openApiJson = ConvertUtils.getJsonMapper().writeValueAsString(openApi);
                    // Create a copy of the keySet so that we can modify the map while in a foreach
                    Set<String> keySet = new HashSet<>(schemas.keySet());
                    for (String schemaName : keySet) {
                        if (!openApiJson.contains("\"" + COMPONENTS_SCHEMAS_REF + schemaName + '"')) {
                            schemas.remove(schemaName);
                        }
                    }
                }
            }
        } catch (JsonProcessingException e) {
            // do nothing
        }

        removeEmtpyComponents(openApi);

        openApi = resolvePropertyPlaceHolders(openApi, context);

        return openApi;
    }

    private void removeEmtpyComponents(OpenAPI openAPI) {
        Components components = openAPI.getComponents();
        if (components == null) {
            return;
        }
        if (CollectionUtils.isEmpty(components.getSchemas())) {
            components.setSchemas(null);
        }
        if (CollectionUtils.isEmpty(components.getResponses())) {
            components.setResponses(null);
        }
        if (CollectionUtils.isEmpty(components.getParameters())) {
            components.setParameters(null);
        }
        if (CollectionUtils.isEmpty(components.getExamples())) {
            components.setExamples(null);
        }
        if (CollectionUtils.isEmpty(components.getRequestBodies())) {
            components.setRequestBodies(null);
        }
        if (CollectionUtils.isEmpty(components.getHeaders())) {
            components.setHeaders(null);
        }
        if (CollectionUtils.isEmpty(components.getSecuritySchemes())) {
            components.setSecuritySchemes(null);
        }
        if (CollectionUtils.isEmpty(components.getLinks())) {
            components.setLinks(null);
        }
        if (CollectionUtils.isEmpty(components.getCallbacks())) {
            components.setCallbacks(null);
        }
        if (CollectionUtils.isEmpty(components.getExtensions())) {
            components.setExtensions(null);
        }

        if (CollectionUtils.isEmpty(components.getSchemas())
            && CollectionUtils.isEmpty(components.getResponses())
            && CollectionUtils.isEmpty(components.getParameters())
            && CollectionUtils.isEmpty(components.getExamples())
            && CollectionUtils.isEmpty(components.getRequestBodies())
            && CollectionUtils.isEmpty(components.getHeaders())
            && CollectionUtils.isEmpty(components.getSecuritySchemes())
            && CollectionUtils.isEmpty(components.getLinks())
            && CollectionUtils.isEmpty(components.getCallbacks())
            && CollectionUtils.isEmpty(components.getExtensions())
        ) {
            openAPI.setComponents(null);
        }
    }

    private void normalizeOpenApi(OpenAPI openAPI) {
        // Sort paths
        if (openAPI.getPaths() != null) {
            io.swagger.v3.oas.models.Paths sortedPaths = new io.swagger.v3.oas.models.Paths();
            new TreeMap<>(openAPI.getPaths()).forEach(sortedPaths::addPathItem);
            if (openAPI.getPaths().getExtensions() != null) {
                sortedPaths.setExtensions(new TreeMap<>(openAPI.getPaths().getExtensions()));
            }
            openAPI.setPaths(sortedPaths);
            for (PathItem pathItem : sortedPaths.values()) {
                normalizeOperation(pathItem.getGet());
                normalizeOperation(pathItem.getPut());
                normalizeOperation(pathItem.getPost());
                normalizeOperation(pathItem.getDelete());
                normalizeOperation(pathItem.getOptions());
                normalizeOperation(pathItem.getHead());
                normalizeOperation(pathItem.getPatch());
                normalizeOperation(pathItem.getTrace());
            }
        }

        // Sort all reusable Components
        Components components = openAPI.getComponents();
        if (components == null) {
            return;
        }

        normalizeSchemas(components.getSchemas());

        sortComponent(components, Components::getSchemas, Components::setSchemas);
        sortComponent(components, Components::getResponses, Components::setResponses);
        sortComponent(components, Components::getParameters, Components::setParameters);
        sortComponent(components, Components::getExamples, Components::setExamples);
        sortComponent(components, Components::getRequestBodies, Components::setRequestBodies);
        sortComponent(components, Components::getHeaders, Components::setHeaders);
        sortComponent(components, Components::getSecuritySchemes, Components::setSecuritySchemes);
        sortComponent(components, Components::getLinks, Components::setLinks);
        sortComponent(components, Components::getCallbacks, Components::setCallbacks);
    }

    private void normalizeOperation(Operation operation) {
        if (operation == null) {
            return;
        }
        if (CollectionUtils.isNotEmpty(operation.getParameters())) {
            for (Parameter parameter : operation.getParameters()) {
                if (parameter == null) {
                    continue;
                }
                Schema<?> paramSchema = parameter.getSchema();
                if (paramSchema == null) {
                    continue;
                }
                Schema<?> normalizedSchema = normalizeSchema(paramSchema);
                if (normalizedSchema != null) {
                    parameter.setSchema(normalizedSchema);
                } else if (paramSchema.equals(EMPTY_SIMPLE_SCHEMA)) {
                    paramSchema.setType(TYPE_OBJECT);
                }
            }
        }
        if (operation.getRequestBody() != null) {
            normalizeContent(operation.getRequestBody().getContent());
        }
        if (CollectionUtils.isNotEmpty(operation.getResponses())) {
            for (ApiResponse apiResponse : operation.getResponses().values()) {
                normalizeContent(apiResponse.getContent());
            }
        }
    }

    private void normalizeContent(Content content) {
        if (CollectionUtils.isEmpty(content)) {
            return;
        }
        for (MediaType mediaType : content.values()) {
            Schema<?> mediaTypeSchema = mediaType.getSchema();
            if (mediaTypeSchema == null) {
                continue;
            }
            Schema<?> normalizedSchema = normalizeSchema(mediaTypeSchema);
            if (normalizedSchema != null) {
                mediaType.setSchema(normalizedSchema);
            } else if (mediaTypeSchema.equals(EMPTY_SIMPLE_SCHEMA)) {
                mediaTypeSchema.setType(TYPE_OBJECT);
            }
            Map<String, Schema> paramSchemas = mediaTypeSchema.getProperties();
            if (CollectionUtils.isNotEmpty(paramSchemas)) {
                Map<String, Schema> paramNormalizedSchemas = new HashMap<>();
                for (Map.Entry<String, Schema> paramEntry : paramSchemas.entrySet()) {
                    Schema<?> paramSchema = paramEntry.getValue();
                    Schema<?> paramNormalizedSchema = normalizeSchema(paramSchema);
                    if (paramNormalizedSchema != null) {
                        paramNormalizedSchemas.put(paramEntry.getKey(), paramNormalizedSchema);
                    }
                }
                if (CollectionUtils.isNotEmpty(paramNormalizedSchemas)) {
                    paramSchemas.putAll(paramNormalizedSchemas);
                }
            }
        }
    }

    private <T> void sortComponent(Components components, Function<Components, Map<String, T>> getter, BiConsumer<Components, Map<String, T>> setter) {
        if (components != null && getter.apply(components) != null) {
            Map<String, T> component = getter.apply(components);
            setter.accept(components, new TreeMap<>(component));
        }
    }

    private Schema<?> normalizeSchema(Schema<?> schema) {
        List<Schema> allOf = schema.getAllOf();
        if (CollectionUtils.isEmpty(allOf)) {
            return null;
        }

        if (allOf.size() == 1) {

            Schema<?> allOfSchema = allOf.get(0);

            schema.setAllOf(null);
            // if schema has only allOf block with one item or only defaultValue property or only type
            Object defaultValue = schema.getDefault();
            String type = schema.getType();
            String serializedDefaultValue;
            try {
                serializedDefaultValue = defaultValue != null ? ConvertUtils.getJsonMapper().writeValueAsString(defaultValue) : null;
            } catch (JsonProcessingException e) {
                return null;
            }
            schema.setDefault(null);
            schema.setType(null);
            Schema<?> normalizedSchema = null;

            Object allOfDefaultValue = allOfSchema.getDefault();
            String serializedAllOfDefaultValue;
            try {
                serializedAllOfDefaultValue = allOfDefaultValue != null ? ConvertUtils.getJsonMapper().writeValueAsString(allOfDefaultValue) : null;
            } catch (JsonProcessingException e) {
                return null;
            }
            boolean isSameType = allOfSchema.getType() == null || allOfSchema.getType().equals(type);

            if (SchemaUtils.isEmptySchema(schema)
                && (serializedDefaultValue == null || serializedDefaultValue.equals(serializedAllOfDefaultValue))
                && (type == null || allOfSchema.getType() == null || allOfSchema.getType().equals(type))) {
                normalizedSchema = allOfSchema;
            }
            schema.setType(type);
            schema.setAllOf(allOf);
            schema.setDefault(defaultValue);
            return normalizedSchema;
        }
        List<Schema> finalList = new ArrayList<>(allOf.size());
        List<Schema> schemasWithoutRef = new ArrayList<>(allOf.size() - 1);
        for (Schema<?> schemaAllOf : allOf) {
            Schema<?> normalizedSchema = normalizeSchema(schemaAllOf);
            if (normalizedSchema != null) {
                schemaAllOf = normalizedSchema;
            }
            Map<String, Schema> paramSchemas = schemaAllOf.getProperties();
            if (CollectionUtils.isNotEmpty(paramSchemas)) {
                Map<String, Schema> paramNormalizedSchemas = new HashMap<>();
                for (Map.Entry<String, Schema> paramEntry : paramSchemas.entrySet()) {
                    Schema<?> paramSchema = paramEntry.getValue();
                    Schema<?> paramNormalizedSchema = normalizeSchema(paramSchema);
                    if (paramNormalizedSchema != null) {
                        paramNormalizedSchemas.put(paramEntry.getKey(), paramNormalizedSchema);
                    }
                }
                if (CollectionUtils.isNotEmpty(paramNormalizedSchemas)) {
                    paramSchemas.putAll(paramNormalizedSchemas);
                }
            }

            if (StringUtils.isEmpty(schemaAllOf.get$ref())) {
                schemasWithoutRef.add(schemaAllOf);
                // remove all description fields, if it's already set in main schema
                if (StringUtils.isNotEmpty(schema.getDescription())
                    && StringUtils.isNotEmpty(schemaAllOf.getDescription())) {
                    schemaAllOf.setDescription(null);
                }
                // remove deplicate default field
                if (schema.getDefault() != null
                    && schemaAllOf.getDefault() != null && schema.getDefault().equals(schemaAllOf.getDefault())) {
                    schema.setDefault(null);
                }
                continue;
            }
            finalList.add(schemaAllOf);
        }
        finalList.addAll(schemasWithoutRef);
        schema.setAllOf(finalList);
        return null;
    }

    /**
     * Sort schemas list in allOf block: schemas with ref must be first, next other schemas.
     *
     * @param schemas all schema components
     */
    private void normalizeSchemas(Map<String, Schema> schemas) {

        if (CollectionUtils.isEmpty(schemas)) {
            return;
        }

        Map<String, Schema> normalizedSchemas = new HashMap<>();

        for (Map.Entry<String, Schema> entry : schemas.entrySet()) {
            Schema<?> schema = entry.getValue();
            Schema<?> normalizedSchema = normalizeSchema(schema);
            if (normalizedSchema != null) {
                normalizedSchemas.put(entry.getKey(), normalizedSchema);
            } else if (schema.equals(EMPTY_SIMPLE_SCHEMA)) {
                schema.setType(TYPE_OBJECT);
            }

            Map<String, Schema> paramSchemas = schema.getProperties();
            if (CollectionUtils.isNotEmpty(paramSchemas)) {
                Map<String, Schema> paramNormalizedSchemas = new HashMap<>();
                for (Map.Entry<String, Schema> paramEntry : paramSchemas.entrySet()) {
                    Schema<?> paramSchema = paramEntry.getValue();
                    Schema<?> paramNormalizedSchema = normalizeSchema(paramSchema);
                    if (paramNormalizedSchema != null) {
                        paramNormalizedSchemas.put(paramEntry.getKey(), paramNormalizedSchema);
                    } else if (paramSchema.equals(EMPTY_SIMPLE_SCHEMA)) {
                        paramSchema.setType(TYPE_OBJECT);
                    }
                }
                if (CollectionUtils.isNotEmpty(paramNormalizedSchemas)) {
                    paramSchemas.putAll(paramNormalizedSchemas);
                }
            }
        }

        if (CollectionUtils.isNotEmpty(normalizedSchemas)) {
            schemas.putAll(normalizedSchemas);
        }
    }

    private void writeYamlToFile(Map<Pair<String, String>, OpenApiInfo> openApiInfos, String documentTitle, VisitorContext context, boolean isYaml) {

        Path viewsDestDirs = null;

        for (OpenApiInfo openApiInfo : openApiInfos.values()) {
            Optional<Path> specFile = openApiSpecFile(openApiInfo.getFilename(), context);
            try (Writer writer = getFileWriter(specFile.orElse(null))) {
                (isYaml ? ConvertUtils.getYamlMapper() : ConvertUtils.getJsonMapper()).writeValue(writer, openApiInfo.getOpenApi());
                if (Utils.isTestMode()) {
                    Utils.setTestFileName(openApiInfo.getFilename());
                    if (isYaml) {
                        Utils.setTestYamlReference(writer.toString());
                    } else {
                        Utils.setTestJsonReference(writer.toString());
                    }
                } else {
                    @SuppressWarnings("OptionalGetWithoutIsPresent")
                    Path specPath = specFile.get();
                    context.info("Writing OpenAPI file to destination: " + specPath);
                    viewsDestDirs = getViewsDestDir(getDefaultFilePath(openApiInfo.getFilename(), context).get(), context);
                    context.info("Writing OpenAPI views to destination: " + viewsDestDirs);
                    final Path viewsDestDirsFinal = viewsDestDirs;
                    context.getClassesOutputPath().ifPresent(path -> {
                        // add relative paths for the specPath, and its parent META-INF/swagger
                        // so that micronaut-graal visitor knows about them
                        context.addGeneratedResource(path.relativize(specPath).toString());
                        context.addGeneratedResource(path.relativize(specPath.getParent()).toString());
                        context.addGeneratedResource(path.relativize(viewsDestDirsFinal).toString());
                    });
                    openApiInfo.setSpecFilePath(specPath.getFileName().toString());
                }
            } catch (Exception e) {
                context.warn("Unable to generate swagger" + (isYaml ? EXT_YML : EXT_JSON) + ": " + specFile.orElse(null) + " - " + e.getMessage() + ".\n" + Utils.printStackTrace(e), classElement);
            }
        }
        if (!Utils.isTestMode() && viewsDestDirs != null) {
            try {
                renderViews(documentTitle, openApiInfos, viewsDestDirs, context);
            } catch (Exception e) {
                context.warn("Unable to render swagger" + (isYaml ? EXT_YML : EXT_JSON) + ": " + openApiInfos.values()
                    .stream()
                    .map(OpenApiInfo::getSpecFilePath)
                    .collect(Collectors.joining(", ", "files ", "")) + " - " + e.getMessage() + ".\n" + Utils.printStackTrace(e), classElement);
            }
        }
    }

    private Writer getFileWriter(Path specFile) throws IOException {
        if (Utils.isTestMode()) {
            return new StringWriter();
        } else if (specFile != null) {
            return Files.newBufferedWriter(specFile, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
        } else {
            throw new IOException("Swagger spec file location is not present");
        }
    }

    private void processEndpoints(VisitorContext context) {
        EndpointsConfiguration endpointsCfg = endpointsConfiguration(context);
        if (endpointsCfg.isEnabled() && CollectionUtils.isNotEmpty(endpointsCfg.getEndpoints())) {
            OpenApiEndpointVisitor visitor = new OpenApiEndpointVisitor(true);
            endpointsCfg.getEndpoints().values().stream()
            .filter(endpoint -> endpoint.getClassElement().isPresent())
            .forEach(endpoint -> {
                ClassElement element = endpoint.getClassElement().get();
                context.put(MICRONAUT_OPENAPI_ENDPOINT_CLASS_TAGS, endpoint.getTags());
                context.put(MICRONAUT_OPENAPI_ENDPOINT_SERVERS, endpoint.getServers());
                context.put(MICRONAUT_OPENAPI_ENDPOINT_SECURITY_REQUIREMENTS, endpoint.getSecurityRequirements());
                visitor.visitClass(element, context);
                element.getEnclosedElements(ElementQuery.ALL_METHODS
                                .modifiers(mods -> !mods.contains(ElementModifier.STATIC) && !mods.contains(ElementModifier.PRIVATE))
                                .named(name -> !name.contains("$"))
                        )
                        .forEach(method -> visitor.visitMethod(method, context));
                });
        }
    }

    static class LowerCamelCasePropertyNamingStrategy extends PropertyNamingStrategies.NamingBase {

        @Serial
        private static final long serialVersionUID = -2750503285679998670L;

        @Override
        public String translate(String propertyName) {
            return propertyName;
        }

    }

    static final class CustomSchema {

        private final List<String> typeArgs;
        private final ClassElement classElement;

        private CustomSchema(List<String> typeArgs, ClassElement classElement) {
            this.typeArgs = typeArgs;
            this.classElement = classElement;
        }

        public List<String> getTypeArgs() {
            return typeArgs;
        }

        public ClassElement getClassElement() {
            return classElement;
        }
    }

    static final class SchemaDecorator {

        private String prefix;
        private String postfix;

        public String getPrefix() {
            return prefix;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix;
        }

        public String getPostfix() {
            return postfix;
        }

        public void setPostfix(String postfix) {
            this.postfix = postfix;
        }
    }
}
