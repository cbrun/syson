/*
 * Copyright (c) 2025 Obeo.
 * This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.eclipse.syson.sysml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.resource.Resource;
import org.eclipse.emf.ecore.resource.ResourceSet;
import org.eclipse.syson.application.configuration.SysONDefaultLibrariesConfiguration;
import org.eclipse.syson.application.configuration.SysONLoadDefaultLibrariesOnApplicationStartConfiguration;
import org.eclipse.syson.sysml.helper.EMFUtils;
import org.eclipse.syson.sysml.impl.MembershipCacheAdapter;
import org.eclipse.syson.sysml.textual.SysMLElementSerializer;
import org.eclipse.syson.sysml.textual.utils.FileNameDeresolver;
import org.eclipse.syson.sysml.textual.utils.Status;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class SysMLRoundTripFormattingTest {

    private static final Path SYSML_RESOURCES = Path.of("src", "test", "resources");

    @Test
    @DisplayName("At least one SysML file exists under test resources")
    void sysmlFilesExist() throws IOException {
        List<Path> sysmlFiles = listSysmlFiles(SYSML_RESOURCES);
        assertFalse(sysmlFiles.isEmpty(), "No .sysml files found under " + SYSML_RESOURCES);
    }

    @DisplayName("Round-trip SysML files with formatting-only differences ignored")
    @ParameterizedTest(name = "{0}")
    @MethodSource("sysmlFiles")
    void roundTripSysmlFile(Path sysmlFile) throws IOException {
        SysmlToAst sysmlToAst = new SysmlToAst(null);
        ASTTransformer transformer = new ASTTransformer();

        String original = Files.readString(sysmlFile, StandardCharsets.UTF_8);
        Resource resource = parse(sysmlToAst, transformer, sysmlFile);
        assertNotNull(resource, "Parsing failed for " + sysmlFile);

        Map<String, Long> countsByType = countByType(resource);
        System.out.println(sysmlFile + " -> " + countsByType);

        String serialized = serializeSysml(resource);
        if (!tokensEqual(original, serialized)) {
            assertEquals(original, serialized, "Non-formatting difference in " + sysmlFile);
        }
    }

    private static Stream<Path> sysmlFiles() throws IOException {
        return listSysmlFiles(SYSML_RESOURCES).stream();
    }

    private static List<Path> listSysmlFiles(Path root) throws IOException {
        if (!Files.exists(root)) {
            return List.of();
        }
        try (var stream = Files.walk(root)) {
            return stream
                    .filter(path -> Files.isRegularFile(path) && path.getFileName().toString().endsWith(".sysml"))
                    .sorted(Comparator.naturalOrder())
                    .toList();
        }
    }

    private static Resource parse(SysmlToAst sysmlToAst, ASTTransformer transformer, Path sysmlFile) throws IOException {
        try (InputStream input = Files.newInputStream(sysmlFile)) {
            InputStream astStream = sysmlToAst.convert(input, "sysml");
            ResourceSet resourceSet = new SysONDefaultLibrariesConfiguration(new SysONLoadDefaultLibrariesOnApplicationStartConfiguration())
                    .getLibrariesResourceSet();
            Resource resource = transformer.convertResource(astStream, resourceSet);
            transformer.logTransformationMessages();
            return resource;
        }
    }

    private static Map<String, Long> countByType(Resource resource) {
        return EMFUtils.eAllContentStreamWithSelf(resource)
                .filter(EObject.class::isInstance)
                .map(EObject.class::cast)
                .collect(Collectors.groupingBy(
                        obj -> obj.eClass().getName(),
                        TreeMap::new,
                        Collectors.counting()));
    }

    private static String serializeSysml(Resource resource) {
        if (resource == null || resource.getContents().isEmpty() || !(resource.getContents().get(0) instanceof Element root)) {
            return "";
        }

        MembershipCacheAdapter membershipCacheAdapter = new MembershipCacheAdapter();
        if (resource.getResourceSet() != null) {
            resource.getResourceSet().eAdapters().add(membershipCacheAdapter);
        } else {
            resource.eAdapters().add(membershipCacheAdapter);
        }

        try {
            List<Status> status = new ArrayList<>();
            String sysmlText = new SysMLElementSerializer(System.lineSeparator(), "\t", new FileNameDeresolver(), status::add)
                    .doSwitch(root);
            if (sysmlText == null) {
                sysmlText = "";
            }
            for (Status s : status) {
                s.log(org.slf4j.LoggerFactory.getLogger(SysMLRoundTripFormattingTest.class));
            }
            return sysmlText;
        } finally {
            if (resource.getResourceSet() != null) {
                resource.getResourceSet().eAdapters().remove(membershipCacheAdapter);
            } else {
                resource.eAdapters().remove(membershipCacheAdapter);
            }
        }
    }

    private static boolean tokensEqual(String left, String right) {
        List<String> leftTokens = Tokenizer.tokenize(left);
        List<String> rightTokens = Tokenizer.tokenize(right);
        if (leftTokens.size() != rightTokens.size()) {
            return false;
        }
        for (int i = 0; i < leftTokens.size(); i++) {
            if (!leftTokens.get(i).equals(rightTokens.get(i))) {
                return false;
            }
        }
        return true;
    }

    private static String tokenLines(String input) {
        List<String> tokens = Tokenizer.tokenize(input);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < tokens.size(); i++) {
            builder.append(i).append(": ").append(tokens.get(i)).append(System.lineSeparator());
        }
        return builder.toString();
    }

    private static final class Tokenizer {
        private Tokenizer() {
        }

        static List<String> tokenize(String input) {
            List<String> tokens = new ArrayList<>();
            int index = 0;
            int length = input.length();
            while (index < length) {
                char c = input.charAt(index);
                if (Character.isWhitespace(c)) {
                    index++;
                    continue;
                }
                if (c == '/' && index + 1 < length) {
                    char next = input.charAt(index + 1);
                    if (next == '/') {
                        index = skipLineComment(input, index + 2);
                        continue;
                    }
                    if (next == '*') {
                        index = skipBlockComment(input, index + 2);
                        continue;
                    }
                }
                if (c == '"' || c == '\'') {
                    int start = index;
                    index = readStringLiteral(input, index);
                    tokens.add(input.substring(start, index));
                    continue;
                }
                if (isIdentifierStart(c)) {
                    int start = index;
                    index++;
                    while (index < length && isIdentifierPart(input.charAt(index))) {
                        index++;
                    }
                    tokens.add(input.substring(start, index));
                    continue;
                }
                if (Character.isDigit(c)) {
                    int start = index;
                    index++;
                    while (index < length && isNumberPart(input.charAt(index))) {
                        index++;
                    }
                    tokens.add(input.substring(start, index));
                    continue;
                }
                String twoChars = index + 1 < length ? input.substring(index, index + 2) : null;
                if (twoChars != null && isTwoCharOperator(twoChars)) {
                    tokens.add(twoChars);
                    index += 2;
                    continue;
                }
                tokens.add(String.valueOf(c));
                index++;
            }
            return tokens;
        }

        private static int skipLineComment(String input, int index) {
            int length = input.length();
            while (index < length) {
                char c = input.charAt(index);
                if (c == '\n' || c == '\r') {
                    break;
                }
                index++;
            }
            return index;
        }

        private static int skipBlockComment(String input, int index) {
            int length = input.length();
            while (index + 1 < length) {
                if (input.charAt(index) == '*' && input.charAt(index + 1) == '/') {
                    return index + 2;
                }
                index++;
            }
            return length;
        }

        private static int readStringLiteral(String input, int index) {
            char quote = input.charAt(index);
            index++;
            int length = input.length();
            while (index < length) {
                char c = input.charAt(index);
                if (c == '\\') {
                    index = Math.min(length, index + 2);
                    continue;
                }
                if (c == quote) {
                    return index + 1;
                }
                index++;
            }
            return length;
        }

        private static boolean isIdentifierStart(char c) {
            return Character.isLetter(c) || c == '_';
        }

        private static boolean isIdentifierPart(char c) {
            return Character.isLetterOrDigit(c) || c == '_';
        }

        private static boolean isNumberPart(char c) {
            return Character.isDigit(c) || c == '.' || c == '_' || c == 'e' || c == 'E' || c == '+' || c == '-';
        }

        private static boolean isTwoCharOperator(String op) {
            return switch (op) {
                case "::", "->", "<=", ">=", "==", "!=", "&&", "||" -> true;
                default -> false;
            };
        }
    }
}
