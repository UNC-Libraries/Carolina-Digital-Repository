/**
 * Copyright 2008 The University of North Carolina at Chapel Hill
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.unc.lib.boxc.auth.api;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Constants related to access principals
 *
 * @author bbpennel
 *
 */
public class AccessPrincipalConstants {

    public final static String PUBLIC_PRINC = "everyone";
    public final static String AUTHENTICATED_PRINC = "authenticated";
    public final static String USER_NAMESPACE = "unc:onyen:";
    // common patron principals which are considered protected and present by default
    public final static Set<String> PROTECTED_PATRON_PRINCIPALS = new HashSet<>(
            Arrays.asList(PUBLIC_PRINC, AUTHENTICATED_PRINC));
    // Namespace prefix for special patron groups
    public final static String PATRON_NAMESPACE = "unc:patron:";
    public final static String IP_PRINC_NAMESPACE = PATRON_NAMESPACE + "ipp:";
    public final static String ADMIN_ACCESS_PRINC = "admin_access";

    public final static Pattern PATRON_PRINC_PATTERN =
            Pattern.compile("(" + PUBLIC_PRINC
                    + "|" + AUTHENTICATED_PRINC
                    + "|" + PATRON_NAMESPACE + ".+)");

    private AccessPrincipalConstants() {
    }
}
