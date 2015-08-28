/**
 *
 * Copyright (c) 2006-2015, Speedment, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); You may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.speedment.core.code.lifecycle;

import com.speedment.api.Speedment;
import com.speedment.codegen.base.Generator;
import com.speedment.codegen.lang.models.Class;
import com.speedment.codegen.lang.models.Constructor;
import com.speedment.codegen.lang.models.File;
import com.speedment.codegen.lang.models.Javadoc;
import com.speedment.codegen.lang.models.Method;
import com.speedment.codegen.lang.models.Type;
import static com.speedment.codegen.lang.models.constants.DefaultAnnotationUsage.OVERRIDE;
import static com.speedment.codegen.lang.models.constants.DefaultJavadocTag.AUTHOR;
import static com.speedment.codegen.lang.models.constants.DefaultType.STRING;
import com.speedment.codegen.lang.models.implementation.JavadocImpl;
import com.speedment.core.code.DefaultJavaClassTranslator;
import com.speedment.api.config.Project;
import com.speedment.core.config.utils.GroovyParser;
import com.speedment.core.runtime.ApplicationMetadata;

/**
 *
 * @author pemi
 */
public class SpeedmentApplicationMetadataTranslator extends DefaultJavaClassTranslator<Project, Class> {

    public static final String METADATA = "Metadata";

    private final String className = typeName(project()) + "Application" + METADATA;

    public SpeedmentApplicationMetadataTranslator(Speedment speedment, Generator cg, Project configEntity) {
        super(speedment, cg, configEntity);
    }

    @Override
    protected Class make(File file) {
        final Method getMetadata = Method.of("getMetadata", STRING)
            .public_()
            .add(OVERRIDE)
            .add("return ");

        GroovyParser.toGroovyLines(project()).forEachOrdered(l -> {
            getMetadata.add("        \"" + l.replace("\"", "\\\"") + "\\n\"+");
        });
        getMetadata.add("\"\";");  // Hack...

        //final Path path = project().getConfigPath();
        return Class.of(className)
            .public_()
            .add(Type.of(ApplicationMetadata.class))
            .add(Constructor.of()
                .public_()
            )
            .add(getMetadata)
            .add(generated());
    }

    @Override
    protected Javadoc getJavaDoc() {
        return new JavadocImpl(getJavadocRepresentText() + GENERATED_JAVADOC_MESSAGE).add(AUTHOR.setValue("Speedment"));
    }

    @Override
    protected String getJavadocRepresentText() {
        return "A Speedment Application Metadata class for the {@link com.speedment.core.config.model.Project}. This class contains the meta data present at code generation time.";
    }

    @Override
    protected String getFileName() {
        return className;
    }

}