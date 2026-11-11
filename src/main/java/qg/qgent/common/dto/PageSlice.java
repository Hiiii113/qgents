package qg.qgent.common.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PageSlice<T> {
    private List<T> data;
    private PageInfo page;
}
