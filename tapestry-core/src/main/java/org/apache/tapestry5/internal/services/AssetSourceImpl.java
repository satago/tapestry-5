// Copyright 2006, 2007, 2008, 2009, 2010, 2011, 2012 The Apache Software Foundation
//
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

package org.apache.tapestry5.internal.services;

import org.apache.tapestry5.Asset;
import org.apache.tapestry5.ComponentResources;
import org.apache.tapestry5.internal.AssetConstants;
import org.apache.tapestry5.internal.TapestryInternalUtils;
import org.apache.tapestry5.ioc.Resource;
import org.apache.tapestry5.ioc.internal.util.CollectionFactory;
import org.apache.tapestry5.ioc.internal.util.InternalUtils;
import org.apache.tapestry5.ioc.internal.util.LockSupport;
import org.apache.tapestry5.ioc.services.SymbolSource;
import org.apache.tapestry5.ioc.services.ThreadLocale;
import org.apache.tapestry5.ioc.util.StrategyRegistry;
import org.apache.tapestry5.services.AssetFactory;
import org.apache.tapestry5.services.AssetSource;
import org.slf4j.Logger;

import java.lang.ref.SoftReference;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@SuppressWarnings("all")
public class AssetSourceImpl extends LockSupport implements AssetSource
{
    private final StrategyRegistry<AssetFactory> registry;

    private final ThreadLocale threadLocale;

    private final Map<String, Resource> prefixToRootResource = CollectionFactory.newMap();

    private final Map<Resource, SoftReference<Asset>> cache = new WeakHashMap<Resource, SoftReference<Asset>>();

    private final SymbolSource symbolSource;

    private final Logger logger;

    private final AtomicBoolean firstWarning = new AtomicBoolean(true);

    public AssetSourceImpl(ThreadLocale threadLocale,

                           Map<String, AssetFactory> configuration, SymbolSource symbolSource, Logger logger)
    {
        this.threadLocale = threadLocale;
        this.symbolSource = symbolSource;
        this.logger = logger;

        Map<Class, AssetFactory> byResourceClass = CollectionFactory.newMap();

        for (Map.Entry<String, AssetFactory> e : configuration.entrySet())
        {
            String prefix = e.getKey();
            AssetFactory factory = e.getValue();

            Resource rootResource = factory.getRootResource();

            byResourceClass.put(rootResource.getClass(), factory);

            prefixToRootResource.put(prefix, rootResource);
        }

        registry = StrategyRegistry.newInstance(AssetFactory.class, byResourceClass);
    }

    public Asset getClasspathAsset(String path)
    {
        return getClasspathAsset(path, null);
    }

    public Asset getClasspathAsset(String path, Locale locale)
    {
        return getAsset(null, path, locale);
    }

    public Asset getContextAsset(String path, Locale locale)
    {
        return getAsset(prefixToRootResource.get(AssetConstants.CONTEXT), path, locale);
    }

    public Asset getAsset(Resource baseResource, String path, Locale locale)
    {
        return getAssetInLocale(baseResource, path, defaulted(locale));
    }

    public Resource resourceForPath(String path)
    {
        return findResource(null, path);
    }

    public Asset getExpandedAsset(String path)
    {
        return getUnlocalizedAsset(symbolSource.expandSymbols(path));
    }

    @Override
    public Asset getComponentAsset(ComponentResources resources, String path)
    {
        assert resources != null;

        assert InternalUtils.isNonBlank(path);

        // First, expand symbols:

        String expanded = symbolSource.expandSymbols(path);

        int dotx = expanded.indexOf(':');

        // We special case the hell out of 'classpath:' so that we can provide warnings today (5.4) and
        // blow up in a useful fashion tomorrow (5.5).

        if (dotx > 0 && !expanded.substring(0, dotx).equalsIgnoreCase(AssetConstants.CLASSPATH))
        {
            return getAssetInLocale(resources.getBaseResource(), expanded, resources.getLocale());
        }

        // No prefix, so implicitly classpath:, or explicitly classpath:

        String restOfPath = expanded.substring(dotx + 1);

        // This is tricky, because a relative path (including "../") is ok in 5.3, since its just somewhere
        // else on the classpath (though you can "stray" out of the "safe" zone).  In 5.4, under /META-INF/assets/
        // it's possible to "stray" out beyond the safe zone more easily, into parts of the classpath that can't be
        // represented in the URL.


        // Ends with trailing slash:
        String metaRoot = "META-INF/assets/" + toPathPrefix(resources.getComponentModel().getLibraryName());

        String metaPath = metaRoot + (restOfPath.startsWith("/")
                ? restOfPath.substring(1)
                : restOfPath);

        // Based on the path, metaResource is where it should exist in a 5.4 and beyond world ... unless the expanded
        // path was a bit too full of ../ sequences, in which case the expanded path is not valid and we adjust the
        // error we write.

        Resource metaResource = findLocalizedResource(null, metaPath, resources.getLocale());

        Asset result = getComponentAsset(resources, expanded, metaResource);

        // This is the best way to tell if the result is an asset for a Classpath resource.

        Resource resultResource = result.getResource();

        if (!resultResource.equals(metaResource))
        {
            if (firstWarning.getAndSet(false))
            {
                logger.error("Packaging of classpath assets has changed in release 5.4; " +
                        "Assets should no longer be on the main classpath, " +
                        "but should be moved to 'META-INF/assets/' or a sub-folder. Future releases of Tapestry may " +
                        "no longer support assets on the main classpath.");
            }

            if (metaResource.getFolder().startsWith(metaRoot))
            {
                logger.error(String.format("Classpath asset '/%s' should be moved to folder '/%s/'.",
                        resultResource.getPath(),
                        metaResource.getFolder()));
            } else
            {
                logger.error(String.format("Classpath asset '/%s' should be moved under folder '/%s', and the relative path adjusted.",
                        resultResource.getPath(),
                        metaRoot));
            }
        }

        return result;
    }

    private Asset getComponentAsset(ComponentResources resources, String expandedPath, Resource metaResource)
    {

        if (expandedPath.contains(":") || expandedPath.startsWith("/"))
        {
            return getAssetInLocale(resources.getBaseResource(), expandedPath, resources.getLocale());
        }

        // So, it's relative to the component.  First, check if there's a match using the 5.4 rules.

        if (metaResource.exists())
        {
            return getAssetForResource(metaResource);
        }

        return getAssetInLocale(resources.getBaseResource(), expandedPath, resources.getLocale());
    }

    private String toPathPrefix(String libraryName)
    {
        return libraryName.equals("") ? "" : libraryName + "/";
    }

    public Asset getUnlocalizedAsset(String path)
    {
        return getAssetInLocale(null, path, null);
    }

    private Asset getAssetInLocale(Resource baseResource, String path, Locale locale)
    {
        return getLocalizedAssetFromResource(findResource(baseResource, path), locale);
    }

    /**
     * @param baseResource
     *         the base resource (or null for classpath root) that path will extend from
     * @param path
     *         extension path from the base resource
     * @return the resource, unlocalized, which may not exist (may be for a path with no actual resource)
     */
    private Resource findResource(Resource baseResource, String path)
    {
        assert path != null;
        int colonx = path.indexOf(':');

        if (colonx < 0)
        {
            Resource root = baseResource != null ? baseResource : prefixToRootResource.get(AssetConstants.CLASSPATH);

            return root.forFile(path);
        }

        String prefix = path.substring(0, colonx);

        Resource root = prefixToRootResource.get(prefix);

        if (root == null)
            throw new IllegalArgumentException(String.format("Unknown prefix for asset path '%s'.", path));

        return root.forFile(path.substring(colonx + 1));
    }

    /**
     * Finds a localized resource.
     *
     * @param baseResource
     *         base resource, or null for classpath root
     * @param path
     *         path from baseResource to expected resource
     * @param locale
     *         locale to localize for, or null to not localize
     * @return resource, which may not exist
     */
    private Resource findLocalizedResource(Resource baseResource, String path, Locale locale)
    {
        Resource unlocalized = findResource(baseResource, path);

        if (locale == null || !unlocalized.exists())
        {
            return unlocalized;
        }

        return localize(unlocalized, locale);
    }

    private Resource localize(Resource unlocalized, Locale locale)
    {
        Resource localized = unlocalized.forLocale(locale);

        return localized != null ? localized : unlocalized;
    }

    private Asset getLocalizedAssetFromResource(Resource unlocalized, Locale locale)
    {
        Resource localized = locale == null ? unlocalized : unlocalized.forLocale(locale);

        if (localized == null || !localized.exists())
            throw new RuntimeException(String.format("Unable to locate asset '%s' (the file does not exist).", unlocalized));

        return getAssetForResource(localized);
    }

    private Asset getAssetForResource(Resource resource)
    {
        try
        {
            acquireReadLock();

            Asset result = TapestryInternalUtils.getAndDeref(cache, resource);

            if (result == null)
            {
                result = createAssetFromResource(resource);
                cache.put(resource, new SoftReference(result));
            }

            return result;
        } finally
        {
            releaseReadLock();
        }
    }

    private Locale defaulted(Locale locale)
    {
        return locale != null ? locale : threadLocale.getLocale();
    }

    private Asset createAssetFromResource(Resource resource)
    {
        // The class of the resource is derived from the class of the base resource.
        // So we can then use the class of the resource as a key to locate the correct asset
        // factory.

        try
        {
            upgradeReadLockToWriteLock();

            // Check for competing thread beat us to it (not very likely!):

            Asset result = TapestryInternalUtils.getAndDeref(cache, resource);

            if (result != null)
            {
                return result;
            }

            Class resourceClass = resource.getClass();

            AssetFactory factory = registry.get(resourceClass);

            return factory.createAsset(resource);
        } finally
        {
            downgradeWriteLockToReadLock();
        }
    }
}
