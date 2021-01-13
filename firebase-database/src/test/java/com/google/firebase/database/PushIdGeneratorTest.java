// Copyright 2021 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.database;

import static com.google.firebase.database.snapshot.ChildKey.MAX_KEY_NAME;
import static com.google.firebase.database.snapshot.ChildKey.MIN_KEY_NAME;
import static org.junit.Assert.assertEquals;

import com.google.firebase.database.core.utilities.PushIdGenerator;
import org.codehaus.plexus.util.StringUtils;
import org.junit.Test;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@org.junit.runner.RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE)
public class PushIdGeneratorTest {

  private static final char MIN_PUSH_CHAR = '-';

  private static final char MAX_PUSH_CHAR = 'z';

  private static final int MAX_KEY_LEN = 786;

  @Test
  public void testSuccessorSpecialValue() {
    assertEquals(
        Character.toString(MIN_PUSH_CHAR),
        PushIdGenerator.successor(String.valueOf(Integer.MAX_VALUE)));
    assertEquals(
        MAX_KEY_NAME,
        PushIdGenerator.successor(
            StringUtils.repeat(Character.toString(MAX_PUSH_CHAR), MAX_KEY_LEN)));
  }

  @Test
  public void testSuccessorBasic() {
    assertEquals("abc" + MIN_PUSH_CHAR, PushIdGenerator.successor("abc"));
    assertEquals(
        "abd",
        PushIdGenerator.successor(
            "abc"
                + StringUtils.repeat(
                    Character.toString(MAX_PUSH_CHAR), MAX_KEY_LEN - "abc".length())));
    assertEquals(
        "abc" + MIN_PUSH_CHAR + MIN_PUSH_CHAR, PushIdGenerator.successor("abc" + MIN_PUSH_CHAR));
  }

  @Test
  public void testPredecessorSpecialValue() {
    assertEquals(
        PushIdGenerator.predecessor(String.valueOf(MIN_PUSH_CHAR)),
        String.valueOf(Integer.MAX_VALUE));
    assertEquals(PushIdGenerator.predecessor(String.valueOf(Integer.MIN_VALUE)), MIN_KEY_NAME);
  }

  @Test
  public void testPredecessorBasicValue() {
    assertEquals(
        PushIdGenerator.predecessor("abc"),
        "abb"
            + StringUtils.repeat(Character.toString(MAX_PUSH_CHAR), MAX_KEY_LEN - "abc".length()));
    assertEquals(PushIdGenerator.predecessor("abc" + MIN_PUSH_CHAR), "abc");
  }
}
