/*
 * JBoss, Home of Professional Open Source
 *
 * Copyright 2015 Red Hat, Inc. and/or its affiliates.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.wildfly.security.auth;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import org.wildfly.security.WildFlyElytronProvider;
import org.wildfly.security.auth.provider.FileSystemSecurityRealm;
import org.wildfly.security.auth.server.ModifiableRealmIdentity;
import org.wildfly.security.authz.Attributes;
import org.wildfly.security.authz.AuthorizationIdentity;
import org.wildfly.security.authz.MapAttributes;
import org.wildfly.security.credential.Credential;
import org.wildfly.security.credential.PasswordCredential;
import org.wildfly.security.evidence.PasswordGuessEvidence;
import org.wildfly.security.password.Password;
import org.wildfly.security.password.PasswordFactory;
import org.wildfly.security.password.interfaces.BCryptPassword;
import org.wildfly.security.password.interfaces.ClearPassword;
import org.wildfly.security.password.interfaces.DigestPassword;
import org.wildfly.security.password.interfaces.OneTimePassword;
import org.wildfly.security.password.interfaces.SaltedSimpleDigestPassword;
import org.wildfly.security.password.interfaces.ScramDigestPassword;
import org.wildfly.security.password.interfaces.SimpleDigestPassword;
import org.wildfly.security.password.spec.ClearPasswordSpec;
import org.wildfly.security.password.spec.DigestPasswordAlgorithmSpec;
import org.wildfly.security.password.spec.EncryptablePasswordSpec;
import org.wildfly.security.password.spec.IteratedSaltedPasswordAlgorithmSpec;
import org.wildfly.security.password.spec.OneTimePasswordSpec;
import org.wildfly.security.password.spec.SaltedPasswordAlgorithmSpec;
import org.wildfly.security.password.util.PasswordUtil;
import org.wildfly.security.util.CodePointIterator;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.Provider;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.wildfly.security.password.interfaces.BCryptPassword.BCRYPT_SALT_SIZE;

/**
 * @author <a href="mailto:psilva@redhat.com">Pedro Igor</a>
 */
public class FileSystemSecurityRealmTest {

    private static final Provider provider = new WildFlyElytronProvider();

    @BeforeClass
    public static void onBefore() throws Exception {
        Security.addProvider(provider);
    }

    @AfterClass
    public static void onAfter() throws Exception {
        Security.removeProvider(provider.getName());
    }

    @Test
    public void testCreateIdentityWithNoLevels() throws Exception {
        FileSystemSecurityRealm securityRealm = new FileSystemSecurityRealm(getRootPath(), 0);
        ModifiableRealmIdentity identity = securityRealm.getRealmIdentityForUpdate("plainUser", null, null);

        assertFalse(identity.exists());

        identity.create();

        assertTrue(identity.exists());
    }

    @Test
    public void testCreateIdentityWithLevels() throws Exception {
        FileSystemSecurityRealm securityRealm = new FileSystemSecurityRealm(getRootPath(), 3);
        ModifiableRealmIdentity identity = securityRealm.getRealmIdentityForUpdate("plainUser", null, null);

        identity.create();

        assertTrue(identity.exists());
    }

    @Test
    public void testCreateAndLoadIdentity() throws Exception {
        FileSystemSecurityRealm securityRealm = new FileSystemSecurityRealm(getRootPath(), 3);
        ModifiableRealmIdentity newIdentity = securityRealm.getRealmIdentityForUpdate("plainUser", null, null);

        newIdentity.create();

        securityRealm = new FileSystemSecurityRealm(getRootPath(false), 3);

        ModifiableRealmIdentity existingIdentity = securityRealm.getRealmIdentityForUpdate("plainUser", null, null);

        assertTrue(existingIdentity.exists());
    }

    @Test
    public void testCreateAndLoadAndDeleteIdentity() throws Exception {
        FileSystemSecurityRealm securityRealm = new FileSystemSecurityRealm(getRootPath(), 3);
        ModifiableRealmIdentity newIdentity = securityRealm.getRealmIdentityForUpdate("plainUser", null, null);

        newIdentity.create();

        securityRealm = new FileSystemSecurityRealm(getRootPath(false), 3);

        ModifiableRealmIdentity existingIdentity = securityRealm.getRealmIdentityForUpdate("plainUser", null, null);

        assertTrue(existingIdentity.exists());

        existingIdentity.delete();

        assertFalse(existingIdentity.exists());

        securityRealm = new FileSystemSecurityRealm(getRootPath(false), 3);

        existingIdentity = securityRealm.getRealmIdentityForUpdate("plainUser", null, null);

        assertFalse(existingIdentity.exists());
    }

    @Test
    public void testCreateIdentityWithAttributes() throws Exception {
        FileSystemSecurityRealm securityRealm = new FileSystemSecurityRealm(getRootPath(), 1);
        ModifiableRealmIdentity newIdentity = securityRealm.getRealmIdentityForUpdate("plainUser", null, null);

        newIdentity.create();

        MapAttributes newAttributes = new MapAttributes();

        newAttributes.addFirst("name", "plainUser");
        newAttributes.addAll("roles", Arrays.asList("Employee", "Manager", "Admin"));

        newIdentity.setAttributes(newAttributes);

        securityRealm = new FileSystemSecurityRealm(getRootPath(false), 1);

        ModifiableRealmIdentity existingIdentity = securityRealm.getRealmIdentityForUpdate("plainUser", null, null);
        AuthorizationIdentity authorizationIdentity = existingIdentity.getAuthorizationIdentity();
        Attributes existingAttributes = authorizationIdentity.getAttributes();

        assertEquals(newAttributes.size(), existingAttributes.size());
        assertTrue(newAttributes.get("name").containsAll(existingAttributes.get("name")));
        assertTrue(newAttributes.get("roles").containsAll(existingAttributes.get("roles")));
    }

    @Test
    public void testCreateIdentityWithClearPassword() throws Exception {
        char[] actualPassword = "secretPassword".toCharArray();
        PasswordFactory factory = PasswordFactory.getInstance(ClearPassword.ALGORITHM_CLEAR);
        ClearPassword clearPassword = (ClearPassword) factory.generatePassword(new ClearPasswordSpec(actualPassword));

        assertCreateIdentityWithPassword(actualPassword, clearPassword);
    }

    @Test
    public void testCreateIdentityWithBcryptCredential() throws Exception {
        PasswordFactory passwordFactory = PasswordFactory.getInstance(BCryptPassword.ALGORITHM_BCRYPT);
        char[] actualPassword = "secretPassword".toCharArray();
        BCryptPassword bCryptPassword = (BCryptPassword) passwordFactory.generatePassword(
                new EncryptablePasswordSpec(actualPassword, new IteratedSaltedPasswordAlgorithmSpec(10, PasswordUtil.generateRandomSalt(BCRYPT_SALT_SIZE)))
        );

        assertCreateIdentityWithPassword(actualPassword, bCryptPassword);
    }

    @Test
    public void testCreateIdentityWithScramCredential() throws Exception {
        char[] actualPassword = "secretPassword".toCharArray();
        byte[] salt = PasswordUtil.generateRandomSalt(BCRYPT_SALT_SIZE);
        PasswordFactory factory = PasswordFactory.getInstance(ScramDigestPassword.ALGORITHM_SCRAM_SHA_256);
        EncryptablePasswordSpec encSpec = new EncryptablePasswordSpec(actualPassword, new IteratedSaltedPasswordAlgorithmSpec(4096, salt));
        ScramDigestPassword scramPassword = (ScramDigestPassword) factory.generatePassword(encSpec);

        assertCreateIdentityWithPassword(actualPassword, scramPassword);
    }

    @Test
    public void testCreateIdentityWithDigest() throws Exception {
        char[] actualPassword = "secretPassword".toCharArray();
        PasswordFactory factory = PasswordFactory.getInstance(DigestPassword.ALGORITHM_DIGEST_SHA_512);
        DigestPasswordAlgorithmSpec dpas = new DigestPasswordAlgorithmSpec("jsmith", "elytron");
        EncryptablePasswordSpec encryptableSpec = new EncryptablePasswordSpec(actualPassword, dpas);
        DigestPassword digestPassword = (DigestPassword) factory.generatePassword(encryptableSpec);

        assertCreateIdentityWithPassword(actualPassword, digestPassword);
    }

    @Test
    public void testCreateIdentityWithSimpleDigest() throws Exception {
        char[] actualPassword = "secretPassword".toCharArray();
        EncryptablePasswordSpec eps = new EncryptablePasswordSpec(actualPassword, null);
        PasswordFactory passwordFactory = PasswordFactory.getInstance(SimpleDigestPassword.ALGORITHM_SIMPLE_DIGEST_SHA_512);
        SimpleDigestPassword tsdp = (SimpleDigestPassword) passwordFactory.generatePassword(eps);

        assertCreateIdentityWithPassword(actualPassword, tsdp);
    }

    @Test
    public void testCreateIdentityWithSimpleSaltedDigest() throws Exception {
        char[] actualPassword = "secretPassword".toCharArray();
        byte[] salt = PasswordUtil.generateRandomSalt(BCRYPT_SALT_SIZE);
        SaltedPasswordAlgorithmSpec spac = new SaltedPasswordAlgorithmSpec(salt);
        EncryptablePasswordSpec eps = new EncryptablePasswordSpec(actualPassword, spac);
        PasswordFactory passwordFactory = PasswordFactory.getInstance(SaltedSimpleDigestPassword.ALGORITHM_PASSWORD_SALT_DIGEST_SHA_512);
        SaltedSimpleDigestPassword tsdp = (SaltedSimpleDigestPassword) passwordFactory.generatePassword(eps);

        assertCreateIdentityWithPassword(actualPassword, tsdp);
    }

    @Test
    public void testCreateIdentityWithEverything() throws Exception {
        FileSystemSecurityRealm securityRealm = new FileSystemSecurityRealm(getRootPath(), 1);
        ModifiableRealmIdentity newIdentity = securityRealm.getRealmIdentityForUpdate("plainUser", null, null);

        newIdentity.create();

        MapAttributes newAttributes = new MapAttributes();

        newAttributes.addFirst("firstName", "John");
        newAttributes.addFirst("lastName", "Smith");
        newAttributes.addAll("roles", Arrays.asList("Employee", "Manager", "Admin"));

        newIdentity.setAttributes(newAttributes);

        List<Credential> credentials = new ArrayList<>();

        PasswordFactory passwordFactory = PasswordFactory.getInstance(BCryptPassword.ALGORITHM_BCRYPT);
        BCryptPassword bCryptPassword = (BCryptPassword) passwordFactory.generatePassword(
                new EncryptablePasswordSpec("secretPassword".toCharArray(), new IteratedSaltedPasswordAlgorithmSpec(10, PasswordUtil.generateRandomSalt(BCRYPT_SALT_SIZE)))
        );

        credentials.add(new PasswordCredential(bCryptPassword));

        byte[] hash = CodePointIterator.ofString("505d889f90085847").hexDecode().drain();
        byte[] seed = "ke1234".getBytes(StandardCharsets.US_ASCII);
        PasswordFactory otpFactory = PasswordFactory.getInstance(OneTimePassword.ALGORITHM_OTP_SHA1);
        OneTimePassword otpPassword = (OneTimePassword) otpFactory.generatePassword(
                new OneTimePasswordSpec(hash, seed, 500)
        );
        credentials.add(new PasswordCredential(otpPassword));

        newIdentity.setCredentials(credentials);

        securityRealm = new FileSystemSecurityRealm(getRootPath(false), 1);
        ModifiableRealmIdentity existingIdentity = securityRealm.getRealmIdentityForUpdate("plainUser", null, null);
        assertTrue(existingIdentity.exists());
        assertTrue(existingIdentity.verifyEvidence(new PasswordGuessEvidence("secretPassword".toCharArray())));

        OneTimePassword otp = (OneTimePassword) existingIdentity.getCredential(PasswordCredential.class, OneTimePassword.ALGORITHM_OTP_SHA1).getPassword();
        assertNotNull(otp);
        assertEquals(OneTimePassword.ALGORITHM_OTP_SHA1, otp.getAlgorithm());
        assertArrayEquals(hash, otp.getHash());
        assertArrayEquals(seed, otp.getSeed());
        assertEquals(500, otp.getSequenceNumber());

        AuthorizationIdentity authorizationIdentity = existingIdentity.getAuthorizationIdentity();
        Attributes existingAttributes = authorizationIdentity.getAttributes();

        assertEquals(newAttributes.size(), existingAttributes.size());
        assertTrue(newAttributes.get("firstName").containsAll(existingAttributes.get("firstName")));
        assertTrue(newAttributes.get("lastName").containsAll(existingAttributes.get("lastName")));
        assertTrue(newAttributes.get("roles").containsAll(existingAttributes.get("roles")));
    }

    @Test
    public void testCredentialReplacing() throws Exception {
        FileSystemSecurityRealm securityRealm = new FileSystemSecurityRealm(getRootPath(), 1);
        ModifiableRealmIdentity identity1 = securityRealm.getRealmIdentityForUpdate("testingUser", null, null);
        identity1.create();

        List<Credential> credentials = new ArrayList<>();

        PasswordFactory passwordFactory = PasswordFactory.getInstance(BCryptPassword.ALGORITHM_BCRYPT);
        BCryptPassword bCryptPassword = (BCryptPassword) passwordFactory.generatePassword(
                new EncryptablePasswordSpec("secretPassword".toCharArray(), new IteratedSaltedPasswordAlgorithmSpec(10, PasswordUtil.generateRandomSalt(BCRYPT_SALT_SIZE)))
        );
        credentials.add(new PasswordCredential(bCryptPassword));

        byte[] hash = CodePointIterator.ofString("505d889f90085847").hexDecode().drain();
        byte[] seed = "ke1234".getBytes(StandardCharsets.US_ASCII);
        PasswordFactory otpFactory = PasswordFactory.getInstance(OneTimePassword.ALGORITHM_OTP_SHA1);
        OneTimePassword otpPassword = (OneTimePassword) otpFactory.generatePassword(
                new OneTimePasswordSpec(hash, seed, 500)
        );
        credentials.add(new PasswordCredential(otpPassword));

        identity1.setCredentials(credentials);

        // checking result
        securityRealm = new FileSystemSecurityRealm(getRootPath(false), 1);
        ModifiableRealmIdentity identity3 = securityRealm.getRealmIdentityForUpdate("testingUser", null, null);

        assertTrue(identity3.exists());
        assertTrue(identity3.verifyEvidence(new PasswordGuessEvidence("secretPassword".toCharArray())));
    }

    @Test
    public void testIterating() throws Exception {
        FileSystemSecurityRealm securityRealm = new FileSystemSecurityRealm(getRootPath(), 1);
        ModifiableRealmIdentity identity1 = securityRealm.getRealmIdentityForUpdate("firstUser", null, null);
        identity1.create();
        ModifiableRealmIdentity identity2 = securityRealm.getRealmIdentityForUpdate("secondUser", null, null);
        identity2.create();
        Iterator<ModifiableRealmIdentity> iterator = securityRealm.getRealmIdentityIterator();

        int count = 0;
        while(iterator.hasNext()){
            ModifiableRealmIdentity identity = iterator.next();
            Assert.assertTrue(identity.exists());
            count++;
        }

        Assert.assertEquals(2, count);
        getRootPath(); // will fail on windows if iterator not closed correctly
    }

    @Test
    public void testPartialIterating() throws Exception {
        FileSystemSecurityRealm securityRealm = new FileSystemSecurityRealm(getRootPath(), 1);
        ModifiableRealmIdentity identity1 = securityRealm.getRealmIdentityForUpdate("firstUser", null, null);
        identity1.create();
        ModifiableRealmIdentity identity2 = securityRealm.getRealmIdentityForUpdate("secondUser", null, null);
        identity2.create();
        Iterator<ModifiableRealmIdentity> iterator = securityRealm.getRealmIdentityIterator();

        Assert.assertTrue(iterator.hasNext());
        Assert.assertTrue(iterator.next().exists());
        Assert.assertTrue(iterator instanceof Closeable);
        ((Closeable) iterator).close();
        getRootPath(); // will fail on windows if iterator not closed correctly
    }

    private void assertCreateIdentityWithPassword(char[] actualPassword, Password credential) throws Exception {
        FileSystemSecurityRealm securityRealm = new FileSystemSecurityRealm(getRootPath(), 1);
        ModifiableRealmIdentity newIdentity = securityRealm.getRealmIdentityForUpdate("plainUser", null, null);

        newIdentity.create();

        newIdentity.setCredentials(Collections.singleton(new PasswordCredential(credential)));

        securityRealm = new FileSystemSecurityRealm(getRootPath(false), 1);

        ModifiableRealmIdentity existingIdentity = securityRealm.getRealmIdentityForUpdate("plainUser", null, null);

        assertTrue(existingIdentity.exists());
        assertTrue(existingIdentity.verifyEvidence(new PasswordGuessEvidence(actualPassword)));
    }

    private Path getRootPath(boolean deleteIfExists) throws Exception {
        Path rootPath = Paths.get(getClass().getResource(File.separator).toURI())
                .resolve("filesystem-realm");

        if (rootPath.toFile().exists() && !deleteIfExists) {
            return rootPath;
        }

        return Files.walkFileTree(Files.createDirectories(rootPath), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Path getRootPath() throws Exception {
        return getRootPath(true);
    }

}
