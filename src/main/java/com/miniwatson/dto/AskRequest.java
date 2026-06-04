package com.miniwatson.dto;
import lombok.Data;


@Data // 어노테이션 하나로 getter/setter/toString 자동 생성. 본인이 안 적어도 됨.
public class AskRequest {
    private String question;

    //Todo getter, setter (Lombok 쓰면 @Data 로 줄임)
}
