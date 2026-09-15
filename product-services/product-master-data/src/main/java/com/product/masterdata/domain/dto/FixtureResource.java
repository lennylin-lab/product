package com.product.masterdata.domain.dto;

import lombok.Data;

/**
 * 夹具资源聚合入参（resource 主行 + fixture 扩展行），字段形态同 {@link MachineResource}。
 *
 * @author product
 * @date 2026-09-15
 */
@Data
public class FixtureResource {
    private Long fixtureId;
    private String orgUnit;
    private String name;
    private String fixtureCode;
    private Long calendarId;
    private String status;
}
