package com.hula.common.event;

import com.hula.core.user.domain.dto.RequestApprovalDto;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * @author nyh
 */
@Getter
public class UserApprovalEvent extends ApplicationEvent {
    private final RequestApprovalDto requestApprovalDto;

    public UserApprovalEvent(Object source, RequestApprovalDto requestApprovalDto) {
        super(source);
        this.requestApprovalDto = requestApprovalDto;
    }

}
