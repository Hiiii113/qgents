package qg.qgent.common.web;

import java.util.List;
import qg.qgent.common.dto.PageInfo;

public record PagedApiResponse<T>(List<T> data, PageInfo page, String requestId) {}
