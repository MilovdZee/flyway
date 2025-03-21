/*-
 * ========================LICENSE_START=================================
 * flyway-core
 * ========================================================================
 * Copyright (C) 2010 - 2025 Red Gate Software Ltd
 * ========================================================================
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =========================LICENSE_END==================================
 */
package org.flywaydb.core.internal.resource.classpath;

import lombok.CustomLog;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.resource.LoadableResource;
import org.flywaydb.core.internal.util.FlywayDbWebsiteLinks;
import org.flywaydb.core.internal.util.UrlUtils;

import java.io.*;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.Enumeration;
import java.util.Objects;

import org.flywaydb.core.internal.resource.filesystem.EncodingDetector;
import org.flywaydb.core.internal.resource.filesystem.FlywayEncodingDetectionException;

@CustomLog
public class ClassPathResource extends LoadableResource {
    private final String fileNameWithAbsolutePath;
    private final String fileNameWithRelativePath;
    private final ClassLoader classLoader;
    private final Charset encoding;
    private final boolean detectEncoding;
    private final String parentURL;

    private final boolean stream;

    public ClassPathResource(Location location, String fileNameWithAbsolutePath, ClassLoader classLoader,
                             Charset encoding) {
        this(location, fileNameWithAbsolutePath, classLoader, encoding, false, "", false);
    }

    public ClassPathResource(Location location, String fileNameWithAbsolutePath, ClassLoader classLoader,
                             Charset encoding, String parentURL, boolean stream) {
        this(location, fileNameWithAbsolutePath, classLoader, encoding, false, parentURL, stream);
    }

    public ClassPathResource(Location location, String fileNameWithAbsolutePath, ClassLoader classLoader,
                             Charset encoding, Boolean detectEncoding, String parentURL, boolean stream) {
        this.fileNameWithAbsolutePath = fileNameWithAbsolutePath;
        this.fileNameWithRelativePath = location == null ? fileNameWithAbsolutePath : location.getPathRelativeToThis(fileNameWithAbsolutePath);
        this.classLoader = classLoader;
        this.encoding = encoding;
        this.detectEncoding = detectEncoding;
        this.parentURL = parentURL;
        this.stream = stream;
    }

    @Override
    public String getRelativePath() {
        return fileNameWithRelativePath;
    }

    @Override
    public String getAbsolutePath() {
        return fileNameWithAbsolutePath;
    }

    @Override
    public String getAbsolutePathOnDisk() {
        URL url = getUrl();
        if (url == null) {
            throw new FlywayException("Unable to find resource on disk: " + fileNameWithAbsolutePath);
        }
        return new File(UrlUtils.decodeURL(url.getPath())).getAbsolutePath();
    }

    private URL getUrl() {
        try {
            Enumeration<URL> urls = classLoader.getResources(fileNameWithAbsolutePath);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if (url.getPath() != null && url.getPath().contains(parentURL)) {
                    return url;
                }
            }
        } catch (IOException e) {
            throw new FlywayException(e);
        }

        return null;
    }

    @Override
    public Reader read() {
        InputStream inputStream = null;
        try {
            Enumeration<URL> urls = classLoader.getResources(fileNameWithAbsolutePath);
            while (urls.hasMoreElements()) {
                URL url = urls.nextElement();
                if (url.getPath() != null && url.getPath().contains(parentURL)) {
                    inputStream = url.openStream();
                    break;
                }
            }
        } catch (IOException e) {
            throw new FlywayException(e);
        }

        if (inputStream == null) {
            throw new FlywayException("Unable to obtain inputstream for resource: " + fileNameWithAbsolutePath);
        }

        Charset charset = encoding;
        if (detectEncoding) {
            try {
                charset = EncodingDetector.detectFileEncoding(Paths.get(fileNameWithAbsolutePath));
            } catch (FlywayEncodingDetectionException e) {
                LOG.warn("Could not detect file encoding: " + e.getMessage() + "\nThis may cause issues with your deployments." +
                                 " We recommend using a consistent and supported encoding for all your files. See " + FlywayDbWebsiteLinks.FILE_ENCODING_HELP);
            }
        }

        return new InputStreamReader(inputStream, charset.newDecoder());
    }

    @Override
    public String getFilename() {
        return fileNameWithAbsolutePath.substring(fileNameWithAbsolutePath.lastIndexOf("/") + 1);
    }

    public boolean exists() {
        return getUrl() != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }

        ClassPathResource that = (ClassPathResource) o;

        return fileNameWithAbsolutePath.equals(that.fileNameWithAbsolutePath) && parentURL.equals(that.parentURL);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileNameWithAbsolutePath, parentURL);
    }

    @Override
    public boolean shouldStream() {
        return stream;
    }

}
