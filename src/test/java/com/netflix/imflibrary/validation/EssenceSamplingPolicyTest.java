package com.netflix.imflibrary.validation;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;

@Test(groups = "unit")
public class EssenceSamplingPolicyTest {

    @Test
    public void testParse() {
        Assert.assertEquals(EssenceSamplingPolicy.parse(null).getMode(), EssenceSamplingPolicy.Mode.NONE);
        Assert.assertEquals(EssenceSamplingPolicy.parse("").getMode(), EssenceSamplingPolicy.Mode.NONE);
        Assert.assertEquals(EssenceSamplingPolicy.parse("none").getMode(), EssenceSamplingPolicy.Mode.NONE);
        Assert.assertEquals(EssenceSamplingPolicy.parse("FIRST").getMode(), EssenceSamplingPolicy.Mode.FIRST);
        Assert.assertEquals(EssenceSamplingPolicy.parse("all").getMode(), EssenceSamplingPolicy.Mode.ALL);
        EssenceSamplingPolicy every = EssenceSamplingPolicy.parse("every:24");
        Assert.assertEquals(every.getMode(), EssenceSamplingPolicy.Mode.EVERY_N);
        Assert.assertEquals(every.getInterval(), 24);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testParseInvalid() {
        EssenceSamplingPolicy.parse("bogus");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void testParseInvalidInterval() {
        EssenceSamplingPolicy.parse("every:abc");
    }

    @Test
    public void testFrameIndices() {
        Assert.assertTrue(EssenceSamplingPolicy.none().frameIndices(100).isEmpty());
        Assert.assertTrue(EssenceSamplingPolicy.first().frameIndices(0).isEmpty());
        Assert.assertEquals(EssenceSamplingPolicy.first().frameIndices(100), Arrays.asList(0));
        Assert.assertEquals(EssenceSamplingPolicy.everyN(10).frameIndices(25), Arrays.asList(0, 10, 20));

        List<Integer> all = EssenceSamplingPolicy.all().frameIndices(5);
        Assert.assertEquals(all, Arrays.asList(0, 1, 2, 3, 4));
    }
}
