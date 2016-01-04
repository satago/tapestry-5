// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.apache.tapestry5.modules;

import org.apache.tapestry5.BooleanHook;
import org.apache.tapestry5.MarkupWriter;
import org.apache.tapestry5.SymbolConstants;
import org.apache.tapestry5.annotations.Path;
import org.apache.tapestry5.internal.InternalConstants;
import org.apache.tapestry5.internal.services.DocumentLinker;
import org.apache.tapestry5.internal.services.ResourceStreamer;
import org.apache.tapestry5.internal.services.ajax.JavaScriptSupportImpl;
import org.apache.tapestry5.internal.services.assets.ResourceChangeTracker;
import org.apache.tapestry5.internal.services.javascript.*;
import org.apache.tapestry5.internal.util.MessageCatalogResource;
import org.apache.tapestry5.ioc.*;
import org.apache.tapestry5.ioc.annotations.Contribute;
import org.apache.tapestry5.ioc.annotations.Primary;
import org.apache.tapestry5.ioc.annotations.Symbol;
import org.apache.tapestry5.ioc.services.FactoryDefaults;
import org.apache.tapestry5.ioc.services.SymbolProvider;
import org.apache.tapestry5.ioc.util.IdAllocator;
import org.apache.tapestry5.json.JSONObject;
import org.apache.tapestry5.services.*;
import org.apache.tapestry5.services.compatibility.Compatibility;
import org.apache.tapestry5.services.compatibility.Trait;
import org.apache.tapestry5.services.javascript.*;
import org.apache.tapestry5.services.messages.ComponentMessagesSource;

import java.util.Locale;

/**
 * Defines the services related to JavaScript and {@link org.apache.tapestry5.services.javascript.JavaScriptStack}s.
 *
 * @since 5.4
 */
public class JavaScriptModule
{
    private final static String ROOT = "${tapestry.asset.root}";

    private final Environment environment;

    private final EnvironmentalShadowBuilder environmentalBuilder;

    public JavaScriptModule(Environment environment, EnvironmentalShadowBuilder environmentalBuilder)
    {
        this.environment = environment;
        this.environmentalBuilder = environmentalBuilder;
    }

    public static void bind(ServiceBinder binder)
    {
        binder.bind(ModuleManager.class, ModuleManagerImpl.class);
        binder.bind(JavaScriptStackSource.class, JavaScriptStackSourceImpl.class);
        binder.bind(JavaScriptStack.class, ExtensibleJavaScriptStack.class).withMarker(Core.class).withId("CoreJavaScriptStack");
        binder.bind(JavaScriptStack.class, ExtensibleJavaScriptStack.class).withMarker(Internal.class).withId("InternalJavaScriptStack");
    }

    /**
     * Contributes the "core" and "internal" {@link JavaScriptStack}s
     *
     * @since 5.2.0
     */
    @Contribute(JavaScriptStackSource.class)
    public static void provideBuiltinJavaScriptStacks(MappedConfiguration<String, JavaScriptStack> configuration,
                                                      @Core JavaScriptStack coreStack,
                                                      @Internal JavaScriptStack internalStack)
    {
        configuration.add(InternalConstants.CORE_STACK_NAME, coreStack);
        configuration.add("internal", internalStack);
    }

    // These are automatically bundles with the core JavaScript stack; some applications may want to add a few
    // additional ones, such as t5/core/zone.
    private static final String[] bundledModules = new String[]{
            "alert", "ajax", "bootstrap", "console", "dom", "events", "exception-frame", "fields", "forms",
            "pageinit", "messages", "utils", "validation"
    };

    /**
     * The core JavaScriptStack has a number of entries:
     * <dl>
     * <dt>requirejs</dt> <dd>The RequireJS AMD JavaScript library</dd>
     * <dt>scriptaculous.js, effects.js</dt> <dd>Optional JavaScript libraries in compatibility mode (see {@link Trait#SCRIPTACULOUS})</dd>
     * <dt>t53-compatibility.js</dt> <dd>Optional JavaScript library (see {@link Trait#INITIALIZERS})</dd>
     * <dt>underscore-library, underscore-module</dt>
     * <dt>The Underscore JavaScript library, and the shim that allows underscore to be injected</dt>
     * <dt>t5/core/init</dt> <dd>Optional module related to t53-compatibility.js</dd>
     * <dt>jquery-library</dt> <dd>The jQuery library</dd>
     * <dt>jquery-noconflict</dt> <dd>Switches jQuery to no-conflict mode (only present when the infrastructure is "prototype").</dd>
     * <dt>jquery</dt> <dd>A module shim that allows jQuery to be injected (and also switches jQuery to no-conflict mode)</dd>
     * <dt>bootstrap.css, tapestry.css, exception-frame.css, tapestry-console.css, tree.css</dt>
     * <dd>CSS files</dd>
     * <dt>t5/core/[...]</dt>
     * <dd>Additional JavaScript modules</dd>
     * <dt>jquery</dt>
     * <dd>Added if the infrastructure provider is "jquery".</dd>
     * </dl>
     *
     * User modules may replace or extend this list.
     */
    @Contribute(JavaScriptStack.class)
    @Core
    public static void setupCoreJavaScriptStack(OrderedConfiguration<StackExtension> configuration,
                                                Compatibility compatibility,
                                                @Symbol(SymbolConstants.JAVASCRIPT_INFRASTRUCTURE_PROVIDER)
                                                String provider)
    {
        configuration.add("requirejs", StackExtension.library(ROOT + "/require.js"));
        configuration.add("underscore-library", StackExtension.library(ROOT + "/underscore-1.8.3.js"));

        if (provider.equals("prototype"))
        {
            final String SCRIPTY = "${tapestry.scriptaculous}";

            add(configuration, StackExtensionType.LIBRARY, SCRIPTY + "/prototype.js");

            if (compatibility.enabled(Trait.SCRIPTACULOUS))
            {
                add(configuration, StackExtensionType.LIBRARY,
                        SCRIPTY + "/scriptaculous.js",
                        SCRIPTY + "/effects.js");
            }
        }

        if (compatibility.enabled(Trait.INITIALIZERS))
        {
            add(configuration, StackExtensionType.LIBRARY, ROOT + "/t53-compatibility.js");
            configuration.add("t5/core/init", new StackExtension(StackExtensionType.MODULE, "t5/core/init"));
        }

        configuration.add("jquery-library", StackExtension.library(ROOT + "/jquery.js"));

        if (provider.equals("prototype"))
        {
            configuration.add("jquery-noconflict", StackExtension.library(ROOT + "/jquery-noconflict.js"));
        }

        add(configuration, StackExtensionType.MODULE, "jquery");

        addCoreStylesheets(configuration, "${" + SymbolConstants.BOOTSTRAP_ROOT + "}/css/bootstrap.css");

        for (String name : bundledModules)
        {
            String full = "t5/core/" + name;
            configuration.add(full, StackExtension.module(full));
        }

        configuration.add("underscore-module", StackExtension.module("underscore"));
    }

    @Contribute(JavaScriptStack.class)
    @Internal
    public static void setupInternalJavaScriptStack(OrderedConfiguration<StackExtension> configuration)
    {

        // For the internal stack, ignore the configuration and just use the Bootstrap CSS shipped with the
        // framework. This is part of a hack to make internal pages (such as ExceptionReport and T5Dashboard)
        // render correctly even when the Bootstrap CSS has been replaced by the application.

        addCoreStylesheets(configuration, ROOT + "/bootstrap/css/bootstrap.css");
    }

    private static void addCoreStylesheets(OrderedConfiguration<StackExtension> configuration, String bootstrapPath)
    {
        add(configuration, StackExtensionType.STYLESHEET,
                bootstrapPath,

                ROOT + "/tapestry.css",

                ROOT + "/exception-frame.css",

                ROOT + "/tapestry-console.css",

                ROOT + "/tree.css");
    }

    private static void add(OrderedConfiguration<StackExtension> configuration, StackExtensionType type, String... paths)
    {
        for (String path : paths)
        {
            int slashx = path.lastIndexOf('/');
            String id = path.substring(slashx + 1);

            configuration.add(id, new StackExtension(type, path));
        }
    }


    /**
     * Builds a proxy to the current {@link JavaScriptSupport} inside this thread's {@link org.apache.tapestry5.services.Environment}.
     *
     * @since 5.2.0
     */
    public JavaScriptSupport buildJavaScriptSupport()
    {
        return environmentalBuilder.build(JavaScriptSupport.class);
    }

    @Contribute(Dispatcher.class)
    @Primary
    public static void setupModuleDispatchers(OrderedConfiguration<Dispatcher> configuration,
                                              ModuleManager moduleManager,
                                              OperationTracker tracker,
                                              ResourceStreamer resourceStreamer,
                                              PathConstructor pathConstructor,
                                              JavaScriptStackSource javaScriptStackSource,
                                              JavaScriptStackPathConstructor javaScriptStackPathConstructor,
                                              LocalizationSetter localizationSetter,
                                              @Symbol(SymbolConstants.MODULE_PATH_PREFIX)
                                              String modulePathPrefix,
                                              @Symbol(SymbolConstants.ASSET_PATH_PREFIX)
                                              String assetPathPrefix)
    {
        configuration.add("Modules",
                new ModuleDispatcher(moduleManager, resourceStreamer, tracker, pathConstructor,
                    javaScriptStackSource, javaScriptStackPathConstructor, localizationSetter, modulePathPrefix,
                    assetPathPrefix, false),
                "after:Asset", "before:ComponentEvent");

        configuration.add("ComnpressedModules",
                new ModuleDispatcher(moduleManager, resourceStreamer, tracker, pathConstructor,
                    javaScriptStackSource, javaScriptStackPathConstructor, localizationSetter, modulePathPrefix,
                    assetPathPrefix, true),
                "after:Modules", "before:ComponentEvent");
    }

    /**
     * Adds page render filters, each of which provides an {@link org.apache.tapestry5.annotations.Environmental}
     * service. Filters
     * often provide {@link org.apache.tapestry5.annotations.Environmental} services needed by
     * components as they render.
     * <dl>
     * <dt>JavascriptSupport</dt>
     * <dd>Provides {@link JavaScriptSupport}</dd>
     * </dl>
     */
    @Contribute(MarkupRenderer.class)
    public void exposeJavaScriptSupportForFullPageRenders(OrderedConfiguration<MarkupRendererFilter> configuration,
                                                          final JavaScriptStackSource javascriptStackSource,
                                                          final JavaScriptStackPathConstructor javascriptStackPathConstructor,
                                                          final Request request)
    {

        final BooleanHook suppressCoreStylesheetsHook = createSuppressCoreStylesheetHook(request);

        MarkupRendererFilter javaScriptSupport = new MarkupRendererFilter()
        {
            public void renderMarkup(MarkupWriter writer, MarkupRenderer renderer)
            {
                DocumentLinker linker = environment.peekRequired(DocumentLinker.class);

                JavaScriptSupportImpl support = new JavaScriptSupportImpl(linker, javascriptStackSource,
                        javascriptStackPathConstructor, suppressCoreStylesheetsHook);

                environment.push(JavaScriptSupport.class, support);

                renderer.renderMarkup(writer);

                environment.pop(JavaScriptSupport.class);

                support.commit();
            }
        };

        configuration.add("JavaScriptSupport", javaScriptSupport, "after:DocumentLinker");
    }

    /**
     * Contributes {@link PartialMarkupRendererFilter}s used when rendering a
     * partial Ajax response.
     * <dl>
     * <dt>JavaScriptSupport
     * <dd>Provides {@link JavaScriptSupport}</dd>
     * </dl>
     */
    @Contribute(PartialMarkupRenderer.class)
    public void exposeJavaScriptSupportForPartialPageRender(OrderedConfiguration<PartialMarkupRendererFilter> configuration,
                                                            final JavaScriptStackSource javascriptStackSource,

                                                            final JavaScriptStackPathConstructor javascriptStackPathConstructor,

                                                            final Request request)
    {
        final BooleanHook suppressCoreStylesheetsHook = createSuppressCoreStylesheetHook(request);

        PartialMarkupRendererFilter javascriptSupport = new PartialMarkupRendererFilter()
        {
            public void renderMarkup(MarkupWriter writer, JSONObject reply, PartialMarkupRenderer renderer)
            {
                IdAllocator idAllocator;

                if (request.getParameter(InternalConstants.SUPPRESS_NAMESPACED_IDS) == null)
                {
                    String uid = Long.toHexString(System.nanoTime());

                    String namespace = "_" + uid;

                    idAllocator = new IdAllocator(namespace);
                } else
                {
                    // When suppressed, work just like normal rendering.
                    idAllocator = new IdAllocator();
                }

                DocumentLinker linker = environment.peekRequired(DocumentLinker.class);

                JavaScriptSupportImpl support = new JavaScriptSupportImpl(linker, javascriptStackSource,
                        javascriptStackPathConstructor, idAllocator, true, suppressCoreStylesheetsHook);

                environment.push(JavaScriptSupport.class, support);

                renderer.renderMarkup(writer, reply);

                environment.pop(JavaScriptSupport.class);

                support.commit();
            }
        };

        configuration.add("JavaScriptSupport", javascriptSupport, "after:DocumentLinker");
    }

    private BooleanHook createSuppressCoreStylesheetHook(final Request request)
    {
        return new BooleanHook()
        {
            @Override
            public boolean checkHook()
            {
                return request.getAttribute(InternalConstants.SUPPRESS_CORE_STYLESHEETS) != null;
            }
        };
    }


    @Contribute(ModuleManager.class)
    public static void setupBaseModules(MappedConfiguration<String, Object> configuration,
                                        @Path("${tapestry.asset.root}/underscore-shim.js")
                                        Resource underscoreShim,

                                        @Path("${tapestry.asset.root}/jquery-shim.js")
                                        Resource jqueryShim,

                                        @Path("${tapestry.asset.root}/typeahead.js")
                                        Resource typeahead,

                                        @Path("${tapestry.asset.root}/moment-2.11.0.js")
                                        Resource moment,

                                        @Path("${" + SymbolConstants.BOOTSTRAP_ROOT + "}/js/transition.js")
                                        Resource transition)
    {
        // The underscore shim module allows Underscore to be injected
        configuration.add("underscore", new JavaScriptModuleConfiguration(underscoreShim));
        configuration.add("jquery", new JavaScriptModuleConfiguration(jqueryShim));

        configuration.add("bootstrap/transition", new AMDWrapper(transition).require("jquery", "$").asJavaScriptModuleConfiguration());

        for (String name : new String[]{"affix", "alert", "button", "carousel", "collapse", "dropdown", "modal",
                "scrollspy", "tab", "tooltip"})
        {
            Resource lib = transition.forFile(name + ".js");

            configuration.add("bootstrap/" + name, new AMDWrapper(lib).require("bootstrap/transition").asJavaScriptModuleConfiguration());
        }

        Resource popover = transition.forFile("popover.js");

        configuration.add("bootstrap/popover", new AMDWrapper(popover).require("bootstrap/tooltip").asJavaScriptModuleConfiguration());

        configuration.add("t5/core/typeahead", new JavaScriptModuleConfiguration(typeahead).dependsOn("jquery"));

        configuration.add("moment", new JavaScriptModuleConfiguration(moment));

    }

    @Contribute(SymbolProvider.class)
    @FactoryDefaults
    public static void setupFactoryDefaults(MappedConfiguration<String, Object> configuration)
    {
        configuration.add(SymbolConstants.JAVASCRIPT_INFRASTRUCTURE_PROVIDER, "prototype");
        configuration.add(SymbolConstants.MODULE_PATH_PREFIX, "modules");
    }

    @Contribute(ModuleManager.class)
    public static void setupFoundationFramework(MappedConfiguration<String, Object> configuration,
                                                @Symbol(SymbolConstants.JAVASCRIPT_INFRASTRUCTURE_PROVIDER)
                                                String provider,
                                                @Path("classpath:org/apache/tapestry5/t5-core-dom-prototype.js")
                                                Resource domPrototype,
                                                @Path("classpath:org/apache/tapestry5/t5-core-dom-jquery.js")
                                                Resource domJQuery)
    {
        if (provider.equals("prototype"))
        {
            configuration.add("t5/core/dom", new JavaScriptModuleConfiguration(domPrototype));
        }

        if (provider.equals("jquery"))
        {
            configuration.add("t5/core/dom", new JavaScriptModuleConfiguration(domJQuery));
        }

        // If someone wants to support a different infrastructure, they should set the provider symbol to some other value
        // and contribute their own version of the t5/core/dom module.
    }

    @Contribute(ModuleManager.class)
    public static void setupApplicationCatalogModules(MappedConfiguration<String, Object> configuration,
                                                      LocalizationSetter localizationSetter,
                                                      ComponentMessagesSource messagesSource,
                                                      ResourceChangeTracker resourceChangeTracker,
                                                      @Symbol(SymbolConstants.COMPACT_JSON) boolean compactJSON)
    {
        for (Locale locale : localizationSetter.getSupportedLocales())
        {
            MessageCatalogResource resource = new MessageCatalogResource(locale, messagesSource, resourceChangeTracker, compactJSON);

            configuration.add("t5/core/messages/" + locale.toString(), new JavaScriptModuleConfiguration(resource));
        }
    }

    /**
     * Contributes 'ConfigureHTMLElement', which writes the attributes into the HTML tag to describe locale, etc.
     * Contributes 'AddBrowserCompatibilityStyles', which writes {@code <style/>} elements into the {@code <head/>}
     * element that modifies the page loading mask to work on IE 8 and IE 9.
     */
    @Contribute(MarkupRenderer.class)
    public static void prepareHTMLPageOnRender(OrderedConfiguration<MarkupRendererFilter> configuration)
    {
        configuration.addInstance("ConfigureHTMLElement", ConfigureHTMLElementFilter.class);
        configuration.add("AddBrowserCompatibilityStyles", new AddBrowserCompatibilityStyles());
    }

}
