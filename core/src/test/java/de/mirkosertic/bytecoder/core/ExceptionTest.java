/*
 * Copyright 2018 Mirko Sertic
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.mirkosertic.bytecoder.core;

import de.mirkosertic.bytecoder.unittest.BytecoderUnitTestRunner;
import de.mirkosertic.bytecoder.unittest.JSAndJVMOnly;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(BytecoderUnitTestRunner.class)
@JSAndJVMOnly
public class ExceptionTest {

    private static void throwException(final int aValue) {
        throw new RuntimeException();
    }

    @Test
    public void testControlFlow() {
        int i = 10;
        try {
            i++;
            throwException(i);
            i++;
        } catch (final Exception e) {
            for (int j=0; j< 5;j++) {
                i++;
            }
        } finally {
            i+=3;
        }
        Assert.assertEquals(19, i, 0);
    }

    @Test
    public void testForLoop() {
        int i = 10;
        for (int j = 0; j<20;j++) {
            i = i + 3;
        }
        Assert.assertEquals(70, i, 0);
    }

    public void complexWithArgument(String aMessage) {
        int i;
        try {
            i = 1;

            if (false != (i!=0) );

            return;
        } catch ( Exception e ) {
            RuntimeException le = new RuntimeException(aMessage);
            throw le;
        }
    }

    @Test
    public void testComplexFlow() {
        complexWithArgument("Hello!");
    }

    @Test
    public void testSimpleTry() {
        int x = 10;
        try {
            x = x * 2;
        } catch (Exception e) {
            System.out.println("Exceptions happened!");
        }
        Assert.assertEquals(20, x, 0);
    }

    private boolean value1;
    private boolean value2;

    @Test
    public void testExceptionWithCondition() {
        boolean value1 = false;
        try {
            value1 = null != System.getProperty("CALLS");
        } catch (Exception e) {

        }
        try {
            value2 = null != System.getProperty("LALA");
        } catch (Exception e) {

        }
        value1 = true;
        value2 = true;
    }

    @Test
    public void testExceptionWithForLoop() {
        int x = 1;
        try {
            for (int y = 0; y < 10; y++) {
                if (y > 0) {
                    return;
                }
            }
        } catch (NullPointerException e) {
            return;
        } catch (Exception e) {
            return;
        }
        return;
    }

    private boolean computeMultiple() {
        try {
            for (int x=0;x<10;x++) {
                if (x > 2) {
                    return true;
                }
                if (x < 2) {
                    return true;
                }
            }
        } catch (Exception e) {
            return true;
        }
        return false;
    }

    @Test
    public void testExceptionMultipleExit() {
        Assert.assertTrue(computeMultiple());
    }

    @Test
    public void testNestedExceptionHandling() {
        int x = 0;
        try {
            x = 1;
            try {
                x = 3;
            } catch (RuntimeException e) {
                return;
            }
        } catch (Exception e) {
            return;
        }
    }
}
