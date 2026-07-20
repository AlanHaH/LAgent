package com.adaptivelearning.profile.application;

import com.adaptivelearning.shared.exception.BusinessException;
import org.junit.jupiter.api.Test;
import java.time.LocalTime;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class AvailabilityPolicyTest {
 @Test void rejectsOverlappingSlots(){var slots=List.of(new AvailabilityPolicy.Slot(1,LocalTime.of(19,0),LocalTime.of(20,0),"HIGH"),new AvailabilityPolicy.Slot(1,LocalTime.of(19,30),LocalTime.of(21,0),"MEDIUM"));assertThatThrownBy(()->AvailabilityPolicy.normalizeAndValidate(slots)).isInstanceOf(BusinessException.class).hasMessageContaining("重叠");}
 @Test void splitsCrossMidnightSlot(){var result=AvailabilityPolicy.normalizeAndValidate(List.of(new AvailabilityPolicy.Slot(7,LocalTime.of(23,50),LocalTime.of(0,20),"MEDIUM")));assertThat(result).hasSize(2);assertThat(result).extracting(AvailabilityPolicy.NormalizedSlot::weekday).containsExactly(7,1);assertThat(result).extracting(AvailabilityPolicy.NormalizedSlot::minutes).containsExactly(10,20);}
 @Test void permitsBoundaryTouch(){assertThat(AvailabilityPolicy.normalizeAndValidate(List.of(new AvailabilityPolicy.Slot(1,LocalTime.of(18,0),LocalTime.of(19,0),"MEDIUM"),new AvailabilityPolicy.Slot(1,LocalTime.of(19,0),LocalTime.of(20,0),"HIGH")))).hasSize(2);}
}
