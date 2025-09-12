/*
 * Copyright (C) 2025 AxionOS
 *
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
 */
package com.android.server.wm;

import java.util.*;

public enum UxType {
    OTHER      (1, "60", false, 0),
    KSWAPD     (2, "65", false, 0),
    SYSTEM     (3, "78", false, 0),
    TOPAPP     (4, "70", true,  1),
    INPUT      (5, "79", true, 10),
    ANIMATOR   (6, "79", true, 10);

    public final int id;
    public final String boostValue;
    public final boolean fifoBoost;
    public final int fifoPrio;

    UxType(int id, String boostValue, boolean fifoBoost, int fifoPrio) {
        this.id = id;
        this.boostValue = boostValue;
        this.fifoBoost = fifoBoost;
        this.fifoPrio = fifoPrio;
    }

    private static final Map<Integer, UxType> LOOKUP = new HashMap<>();
    static {
        for (UxType t : values()) {
            LOOKUP.put(t.id, t);
        }
    }

    public static UxType fromId(int id) {
        return LOOKUP.get(id);
    }
}
